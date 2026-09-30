# AGENTS.md — working agreement for this repository

Conventions any contributor must follow, human or AI. These are not style preferences; most of them exist
because breaking them breaks a correctness guarantee.

For *why* the system is built this way, read [README.md](README.md). For the record of how it was built,
see [`docs/ai/`](docs/ai/).

---

## Non-negotiable rules

### 1. Seat allocation is guarded by PostgreSQL row locks

`SELECT … FOR UPDATE`, via `@Lock(LockModeType.PESSIMISTIC_WRITE)` on
`ShowSeatRepository.lockByShowAndSeatIds`. **Do not** replace or supplement this with `synchronized`,
`ReentrantLock`, a distributed lock, or an application-level cache. Each of those either coordinates only
one JVM or introduces a second source of truth that can disagree with the database.

If you touch the hold path:

- seat ids are **sorted** before locking, and the query carries `ORDER BY ss.seat.id`. Both are required —
  they are what stops overlapping multi-seat requests deadlocking;
- the discount code is locked **after** the seats, never before. A fixed global lock order is the point;
- validation and mutation must both happen **after** the lock and **before** the commit that releases it.
  A check performed outside that window guarantees nothing.

### 2. Never hold a transaction open across a network call

`BookingPaymentService` and `BookingCancellationService` hold no `@Transactional` annotation. They
delegate to a *separate* `…TransactionService` bean, with the gateway call between two committed
transactions.

This must stay two beans. Spring's `@Transactional` works through a proxy, so a method calling another
method on `this` bypasses it — merging the orchestration into the transactional class would silently
produce one long transaction spanning the gateway call, with row locks held throughout.

### 3. A transaction that records a failure must commit

Business failures inside a transactional method are **returned**, not thrown — see `PaymentSettlement`,
`PaymentPreparation`, `CancellationOutcome`. The orchestrator raises the HTTP error afterwards.

Throwing rolls back the very record you just wrote. This has already caused one real bug: `prepare()`
expired a booking, released its seats, then threw `HOLD_EXPIRED` — undoing the cleanup. If you add a new
failure path inside a transaction, check whether anything was mutated first.

### 4. Time comes from the injected `Clock`

No `Instant.now()`, `LocalDate.now()` or `System.currentTimeMillis()` in production code. Hold expiry,
discount windows, refund bands and weekend pricing all read the `Clock` bean, which is what lets tests
advance time instead of sleeping.

Bean validation reads it too, via `ValidationConfig` — otherwise `@Future` would disagree with the service
layer about what time it is.

### 5. Money is `BigDecimal`, normalised before arithmetic

Use `Money`. Never `double` or `float`. Normalise to scale 2 *before* computing, not after:
`ck_bookings_total_is_net` requires `total = subtotal - discount` to hold against the values PostgreSQL
actually stored. `MoneyTest.subtractMatchesStoredValues` pins the case that breaks otherwise.

### 6. Flyway owns the schema

`spring.jpa.hibernate.ddl-auto=validate`. Never `create` or `update`, in any profile.

Schema changes are new `V*.sql` files in `db/migration`; applied migrations are never edited. Demo data
lives in `db/seed` (version 900+) and is excluded from the test profile so no test can be satisfied by
data it did not create.

### 7. Entities never leave a transaction

Services return DTOs assembled while the session is open. A controller must never touch a lazy
association — if it can, the mapping is in the wrong place.

### 8. Every URL is a constant in `ApiEndpoints`

No literal paths in controllers, and no class-level `@RequestMapping` — each handler carries its full
path constant. The same constants are reused by `SecurityConfig` and the tests, so a path cannot drift
between routing, security rules and tests.

### 9. Don't leak internals in errors

`GlobalExceptionHandler` returns `ApiErrorResponse` and nothing else. Stack traces, SQL, constraint names
and entity class names are logged server-side only. Security exceptions are deliberately **re-thrown** so
`ExceptionTranslationFilter` can decide 401 versus 403.

### 10. `@ConditionalOnMissingBean` only inside auto-configuration

On a component-scanned bean it depends on registration order and can silently skip registration — which
has already caused one startup failure here. To override a bean in tests, declare it `@Primary`.

---

## Layout and style

**Organise by domain, not by layer.** A feature lives in one package with its entity, repository, service,
DTOs and controller. Cross-domain access goes through the owning service, never another domain's
repository.

**Entities**

```java
@Entity
@Getter
@Builder
@Table(name = "bookings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Booking extends VersionedEntity {

    @Column(columnDefinition = "VARCHAR(30)", name = "booking_reference", nullable = false, length = 30)
    private String bookingReference;
```

- `@Column` carries an explicit `columnDefinition` and `name`, matching the migration exactly.
- No public setters. State changes go through intention-revealing methods (`confirm`, `release`,
  `markRefunded`) that enforce their own invariants.
- Associations are `FetchType.LAZY` with a named `@ForeignKey`.
- Extend `BaseEntity`, or `VersionedEntity` where an optimistic lock is wanted.

**DTOs** are records with static `from(entity)` factories. Requests carry Jakarta Bean Validation for
anything judgeable from the request alone; anything about shared state is a service-layer check reported
as 409 or 422, not 400.

**Services** use constructor injection via `@RequiredArgsConstructor`, `final` fields, and carry explicit
`@Transactional` boundaries. `@Transactional(readOnly = true)` on reads.
`@Transactional(propagation = MANDATORY)` where a method is only correct inside a caller's transaction —
`DiscountService` and `NotificationService` both use it, so calling them unprotected fails immediately
rather than quietly.

**Enums over strings**, always — including `ErrorCode` for failures and `SecurityExpressions` for
`@PreAuthorize`, because those expressions are runtime-evaluated and a typo would fail to *authorize*
rather than fail to compile.

**Comments explain why, not what.** Assume the reader can read Java. Document the decision, the trade-off,
or the constraint that makes the code look the way it does.

---

## Testing

- `*Test` — unit, surefire, no Docker.
- `*IT` — integration, failsafe, Testcontainers PostgreSQL.

**Do not introduce H2.** The guarantees under test are `FOR UPDATE` blocking semantics, `SKIP LOCKED`,
partial indexes and `NULLS NOT DISTINCT` — none of which H2 reproduces. A green H2 suite would prove the
code runs, not that it is correct.

**Do not put `@Transactional` on an integration test.** The behaviour under test is what one transaction
sees of another's *commits*; a test-managed transaction hides exactly that. Isolation comes from
`DatabaseCleaner` truncating between tests.

When asserting a database-level guarantee, assert it **in SQL**, bypassing the ORM — see
`SchemaMigrationIT`. Claiming "the database guarantees X" is only worth something if X holds against a
statement that did not go through application validation.

Concurrency tests release every thread from a shared `CountDownLatch`. Without that gate the first task
finishes before the last starts and no contention occurs, so the test passes while proving nothing.

**If you change the locking, verify the concurrency test can still fail.** Remove
`@Lock(PESSIMISTIC_WRITE)`, confirm `SeatHoldConcurrencyIT` goes red, then restore it. A concurrency test
that has quietly stopped detecting anything is worse than none.

---

## Before opening a PR

```bash
mvn clean verify     # 106 unit + 109 integration, ~45s
```

Check that: migrations are additive and never edited in place; new endpoints are in `ApiEndpoints` and
covered by the security rules; new failure modes have an `ErrorCode` with a deliberate HTTP status; and
the README's assumptions table still reflects what the code does.
