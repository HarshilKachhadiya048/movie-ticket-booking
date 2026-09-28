# Implementation plan and build log

The thirteen-phase order from [`master-prompt.md`](master-prompt.md) §14, with what actually happened in
each phase. Phases are listed in the order they were executed.

Deviations from the plan are called out explicitly. Where something went wrong, it is recorded rather than
tidied away — the failures are the most informative part of a build log.

---

## Phase 0 — Toolchain verification *(not in the original plan)*

Added because the stack is a set of recent majors — Spring Boot 4.1.1, Hibernate 7.4, Spring Security 7.1,
Jackson 3.1, JUnit 6, Testcontainers 2.0 — where a lot of APIs have moved. Assuming the previous majors'
signatures would have meant repeated compile failures spread across the whole build.

Checked up front, against the actual jars:

| Question | Answer |
|---|---|
| Does Spring Boot 4.1.1 exist and resolve? | Yes. |
| Does Hibernate 7 offer a true UUIDv7 generator? | Yes — `@UuidGenerator(algorithm = UuidVersion7Strategy.class)`. `Style.TIME` is not the same thing. |
| Where does Jackson 3 live? | Annotations stay in `com.fasterxml.jackson.annotation`; databind moved to `tools.jackson.databind`. Boot auto-configures a `JsonMapper`, which extends `ObjectMapper`. |
| Is `JacksonException` checked? | No — unchecked in Jackson 3, so no `try/catch` noise. |
| Is `PostgreSQLContainer` still generic? | No — non-generic in Testcontainers 2, and it moved to `org.testcontainers.postgresql`. |
| Which Flyway version, and is the Postgres module separate? | 12.4.0, and yes. |

**Worth the detour.** Three of these would otherwise have surfaced as confusing failures much later.

## Phase 1 — Project setup, configuration, database, Flyway, error model

- `pom.xml`: dependencies, plus surefire/failsafe split so `*Test` runs without Docker and `*IT` runs with
  it.
- Six domain-split migrations (`V1`–`V6`) and `V900` demo data in a separate `db/seed` location, excluded
  from the test profile.
- `ApiEndpoints`, `ErrorCode`, `DomainException`, `ApiErrorResponse`, `GlobalExceptionHandler`, `Money`,
  `BaseEntity`, `VersionedEntity`, and the configuration property records.
- `Dockerfile` and `docker-compose.yml`.

**Deviation:** migrations were applied to a throwaway PostgreSQL 16 container and the seed data queried
directly, before any Java was written against them. That caught a seed-data bug early — the show intended
to demonstrate the 0% refund band was scheduled on the one theater with a *more generous* policy
override, so it would have demonstrated 100%.

## Phase 2 — Users, security, RBAC

`User`, `Role`, `AppUserDetails`, `AppUserDetailsService`, `SecurityConfig`, plus
`RestAuthenticationEntryPoint` / `RestAccessDeniedHandler` so that a 401 raised inside the filter chain
returns the same JSON shape as a 409 raised inside a service.

`GlobalExceptionHandler` deliberately **re-throws** security exceptions rather than handling them, so
`ExceptionTranslationFilter` decides 401 versus 403 — handling them in the advice would turn every missing
credential into a misleading 403.

**First compile.** One failure: `HandlerMethodValidationException.getAllValidationResults()` is
`getParameterValidationResults()` in Spring 7.

## Phase 3–4 — Catalogue and show-seat inventory

Entities for city, movie, theater, screen, seat, show and `ShowSeat`, then every repository.

`ShowSeat` is where the invariants live rather than being trusted to callers: `hold()` refuses to
overwrite a live hold, `release()` and `confirm()` clear *every* hold column to satisfy
`ck_show_seats_hold_fields`, and `effectiveStatus(now)` applies lazy expiry.

`ShowSeatRepository.lockByShowAndSeatIds` carries `@Lock(PESSIMISTIC_WRITE)` and `ORDER BY ss.seat.id`.
It is written as a single-table query on purpose — `ss.show.id` and `ss.seat.id` resolve to foreign-key
columns, so no join is emitted and the `FOR UPDATE` stays clean.

## Phase 5 — Pricing and discounts

`PricingService` resolves the most specific active rule per seat category from a **single** candidate
query, so pricing a ten-seat booking still costs one round trip. Day type is derived from the show's
instant projected into the *city's* zone.

`DiscountService` locks the code row before checking its limits.

**A subtlety that needed a design change:** the code must be resolved to an id first
(`findIdByCode`, a scalar projection) and only then loaded by the locking query. Loading the entity first
and locking it afterwards leaves the already-managed instance in the persistence context, and Hibernate
returns *that* rather than refreshing — so the limit check could have run against a stale counter.

## Phase 6 — Seat hold and concurrency

`SeatHoldService`, following the specified ten steps. `CreateHoldCommand` keeps the service decoupled from
the HTTP request shape.

**Deviation:** the service returns a `BookingResponse` rather than the entity, so the response is assembled
while the session is open. Returning the entity would have pushed lazy-loading problems into the
controller.

## Phase 7–8 — Payment and confirmation

Split into `BookingPaymentService` (orchestration, **no** `@Transactional`) and
`BookingPaymentTransactionService` (the two transactions). Two beans, not one, because
`@Transactional` works through a proxy — merging them would have silently produced one long transaction
spanning the gateway call.

Business failures are **returned** rather than thrown, so the transaction that records them commits.

**Deviation from the plan:** the specified flow does not cover the case where the hold lapses *during* the
gateway call and the charge succeeds anyway. Left unhandled, that takes a customer's money and gives them
nothing. Added as an explicit `HOLD_LAPSED_AFTER_CHARGE` outcome that triggers a compensating refund
outside the transaction, recorded as a normal `refunds` row.

## Phase 9 — Cancellation and refunds

`RefundCalculator` (pure arithmetic, no repository or clock), `RefundService` (policy resolution), and
`BookingCancellationService` / `…TransactionService` in the same two-bean shape.

Refund bands are half-open `[min, max)` so adjacent bands share an endpoint without overlapping or leaving
a gap, and a cancellation at exactly 24 hours has one answer. Hours are computed from seconds, not rounded
to whole hours. Everything fails closed: no policy, or a gap, yields 0%.

## Phase 10–11 — Notifications and reminders

Notification rows are written inside the business transaction and dispatched after commit on the bounded
executor.

**Deviation:** neither stock rejection policy is usable. `CallerRunsPolicy` would run delivery on the HTTP
request thread — the one thing the specification forbids. `AbortPolicy` throws while submitting from
inside an after-commit callback, which would surface as a **500 on a booking that already committed
successfully**. Replaced with a handler that logs and discards; the row stays `PENDING` and nothing
recoverable is lost.

Reminders are idempotent three ways: the claim filters on `reminder_sent_at IS NULL`, uses
`FOR UPDATE OF b SKIP LOCKED`, and the notification's unique `dedupe_key` is the actual guarantee.

## Phase 12 — Tests

Written in dependency order, each suite run as it was written rather than all at the end.

Bugs found by tests, not by reading code:

1. **Flyway was never running.** `spring-boot-flyway` is a separate artifact in Boot 4, and without it
   Flyway sits on the classpath doing nothing. The first integration test failed with
   `Schema validation: missing table [booking_seats]`. In production this would have started the
   application against an empty schema.
2. **`@ConditionalOnMissingBean` on a component-scanned bean** silently skipped registering
   `LoggingNotificationSender`, so the context failed to start. That annotation is only reliable inside
   auto-configuration. Removed from there and from `ClockConfig`; tests override with `@Primary`.
3. **`prepare()` rolled back its own cleanup.** It expired the booking and released its seats, then threw
   `HOLD_EXPIRED` — undoing both. Caught by
   `HoldExpiryIT.payingAfterTheHoldLapsedIsRejectedAndTheSeatsAreFreed`, which found the booking still in
   `HOLD_CREATED`. Fixed by adding `PaymentPreparation.Kind.HOLD_EXPIRED` as a returned outcome.
4. **Bean validation ignored the injected clock.** `@Future` is evaluated against a `ClockProvider` that
   defaults to the system clock, so admin show creation was rejected as "in the past" while the service
   layer considered it comfortably in the future. A real inconsistency, not just a test problem. Fixed in
   `ValidationConfig`.
5. **Refund bands were returned in request order.** `@OrderBy` only applies when a collection is loaded
   from the database, not when it was populated in this session. Fixed by sorting on the way in.
6. **The test clock was a Thursday**, so the default fixture's "48 hours out" show landed on a Saturday in
   Asia/Kolkata and picked up weekend prices. Moved to a Monday so weekday pricing is the unremarkable
   default and weekend pricing has to be asked for.

**Verifying the concurrency test can fail.** `@Lock(PESSIMISTIC_WRITE)` was removed and the suite re-run.
It went red — and informatively: the `@Version` column caught the race instead, confirming both that the
test detects the missing lock and that the defence-in-depth layers are real. Restored and re-verified.

## Phase 13 — Documentation

README, `AGENTS.md`, `CLAUDE.md` and this directory.

**Deviation:** before documenting, the application was started against a real PostgreSQL and the full
`curl` walkthrough executed end to end. The outputs quoted in README §19 — including the pricing cascade
resolving 750 on the IMAX screen versus 540 on another Mumbai screen, and the delivery thread names
proving notifications run off the request thread — are from that run, not written from the code.

---

## Final state

```
mvn clean verify
  surefire  106 tests   unit, no Docker
  failsafe  109 tests   integration, Testcontainers PostgreSQL 16
  BUILD SUCCESS  ~45s
```

163 main source files (~10.7k lines), 29 test files (~5.1k lines), 7 migrations, 18 tables.

Every item in the [quality gate](master-prompt.md#15-quality-gate) is covered by a test, with the single
exception of "the application starts successfully", which was verified by hand and is recorded above.
