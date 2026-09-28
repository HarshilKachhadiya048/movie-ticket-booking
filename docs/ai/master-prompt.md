# Master specification

Written before any code existed, and the source of every architectural decision in this repository.
Reproduced here in condensed form.

---

## 1. The system

A movie ticket booking platform: multiple cities, theaters per city, shows per theater, seat-level
booking, time-bound seat holds with automatic expiry, pricing tiers with weekend rates, discount codes,
payment, confirmation, refunds under configurable policies, concurrent users competing for the same seats
with **no double allocation**, asynchronous confirmation and reminder notifications, and admin/customer
roles.

Admin manages cities, theaters, screens, seat layouts, shows, pricing, discount codes and refund policies.
Customers browse, view seat availability, hold seats, pay, confirm, cancel and view their history.

Mandatory: REST APIs, relational persistence, role-based access control, input validation, centralised
error handling, unit tests, integration tests, and a concurrency test for seat booking.

Explicitly out of scope: frontend, deployment, CI/CD, microservices, distributed-system architecture,
OAuth/SSO/MFA, production observability.

## 2. Stack

Java 21 (Temurin), Spring Boot 4.1.1, Maven, Spring MVC, Spring Data JPA, Hibernate, PostgreSQL, Flyway,
Spring Security, Jakarta Bean Validation, JUnit 5, Mockito, Spring Boot Test, Testcontainers PostgreSQL.

**Do not introduce** Redis, Kafka, RabbitMQ, SQS, LocalStack, MongoDB, WebFlux or microservices unless
there is a compelling requirement that cannot be met with the selected stack.

Architecture: a **modular monolith**, organised primarily by business domain — `common`, `user`, `city`,
`theater`, `seat`, `show`, `pricing`, `discount`, `booking`, `payment`, `refund`, `notification` — each
with its own controller, service, repository, entity and DTOs. JPA entities are never exposed from
controllers. Prefer immutable DTOs and records. Clear service-layer transaction boundaries. No business
logic in controllers.

## 3. Database

PostgreSQL is the source of truth. Flyway migrations under `src/main/resources/db/migration`.
`spring.jpa.hibernate.ddl-auto=validate` — never `create` or `update`. UUID identifiers unless there is a
strong reason otherwise. Proper foreign keys, unique constraints, indexes and not-null constraints.
`BigDecimal` for money, never floating point. Timestamps stored consistently as UTC.

Minimum tables: `users`, `cities`, `theaters`, `screens`, `seats`, `shows`, `show_seats`, `pricing_rules`,
`discount_codes`, `bookings`, `booking_seats`, `payments`, `refund_policies`, `refunds`, `notifications`.
Optionally `outbox_events` if durable async notifications are implemented.

## 4. Seat concurrency — the most important requirement

Two concurrent users must **never** successfully allocate the same seat for the same show.

`show_seats` is the inventory table, with `show_id`, `seat_id`, `status`
(`AVAILABLE` / `HELD` / `BOOKED`), `hold_token`, `held_by_user_id`, `hold_expires_at`, `version`, and
`UNIQUE(show_id, seat_id)`.

**The authoritative mechanism must be PostgreSQL row-level locking** — JPA
`@Lock(LockModeType.PESSIMISTIC_WRITE)` or an explicit `SELECT … FOR UPDATE`.
**Do not** use `synchronized`, `ReentrantLock` or Redis locks as the authoritative booking mechanism.

The hold transaction must:

1. sort seat ids deterministically before locking, to reduce deadlock risk;
2. begin a transaction;
3. lock all requested `show_seat` rows;
4. validate that all requested rows exist;
5. treat a `HELD` row whose `hold_expires_at` has passed as logically available;
6. reject if any requested seat is actively `HELD` by another user or already `BOOKED`;
7. generate a unique hold token;
8. set status `HELD`, the holder, and `hold_expires_at` from a configurable duration;
9. create the booking state;
10. commit.

Lock only the specific `show_seats` rows — never the whole `shows` or `seats` table. Do not hold a
database transaction open while calling the payment gateway. Prefer `READ COMMITTED` plus explicit row
locking over `SERIALIZABLE`.

## 5. Hold expiry

Hold duration configurable (`booking.hold-duration=5m`). Two mechanisms:

- **lazy expiry** — an expired `HELD` state is treated as available when the row is locked during a new
  booking request;
- **scheduled cleanup** — `@Scheduled` periodic sweep.

**The scheduler must not be the only mechanism that makes an expired seat bookable.** The system must
remain correct if the scheduler runs late.

## 6. Booking state machine

Explicit states — `HOLD_CREATED`, `PAYMENT_PENDING`, `CONFIRMED`, `CANCELLED`, `PAYMENT_FAILED`,
`REFUNDED` — refinable if a better design exists. Valid transitions enforced in the domain layer;
arbitrary transitions must not be possible.

## 7. Payment

No real provider. A `PaymentGateway` interface with a `MockPaymentGateway` implementation supporting
deterministic success and failure, plus refunds.

Flow:

```
Transaction 1: lock seats, create hold, commit
Payment:       call PaymentGateway
Transaction 2: lock held seats, verify hold token / user / expiry,
               mark booking confirmed, seats BOOKED, persist payment result, commit
```

On failure: release the hold, mark the booking and payment appropriately, persist the failure.

## 8. Pricing, discounts, refunds

**Pricing** must not be hard-coded. A `PricingService` supporting regular, premium and weekend pricing,
keyed on seat category and day type, with `BigDecimal` prices. Calculate subtotal, discount and final
total. **Persist the calculated price snapshot on `booking_seats`** so historical bookings are unaffected
by future pricing changes.

**Discount codes**: percentage and fixed amount; configurable active flag, validity window, minimum
booking amount, maximum discount, overall usage limit and per-user limit. Invalid and expired codes must
be rejected. Prevent usage-limit races using database transactions and constraints.

**Refund policies** must be configurable — e.g. >24h = 100%, 12–24h = 50%, <12h = 0% — and **not
hard-coded**. `RefundPolicy` entities and a `RefundService`. Cancellation: load booking, validate
ownership and state, compute time to show, resolve policy, calculate refund, invoke the mock refund,
persist it, mark the booking, release the seats, and publish the notification event after the transaction
succeeds. Make cancellation idempotent where appropriate.

## 9. Security

Spring Security with HTTP Basic (no OAuth/JWT/SSO/MFA) and BCrypt hashing. Roles `ROLE_ADMIN` and
`ROLE_CUSTOMER`. Seeded development users. Admin endpoints require ADMIN; customer endpoints require
CUSTOMER. Method-level security where useful. **A customer must only be able to access their own
bookings.**

## 10. API, validation, errors

Clean REST endpoints covering the customer flows (`/cities`, `/cities/{id}/theaters`,
`/theaters/{id}/shows`, `/shows/{id}`, `/shows/{id}/seats`, `/holds`, `/bookings/{id}/payment`,
`/bookings/{id}/cancel`, `/bookings`) and admin management of the full catalogue and configuration.
Request/response DTOs, appropriate HTTP status codes, and **409 Conflict for seat availability,
concurrency and domain conflicts**.

Jakarta Bean Validation with a consistent structured error response. Centralised exception handling with
domain error codes (`SEAT_ALREADY_HELD`, `HOLD_EXPIRED`, `BOOKING_NOT_CANCELLABLE`,
`UNAUTHORIZED_BOOKING_ACCESS`, and so on). **Never leak stack traces or internal implementation details.**

## 11. Asynchronous notifications

Booking confirmation and reminders must not block the booking flow. **Do not introduce SQS, Kafka or Redis
for the baseline.** Use `@TransactionalEventListener(phase = AFTER_COMMIT)` combined with
`@Async("notificationExecutor")`, on a dedicated **bounded** `ThreadPoolTaskExecutor`
(core 4, max 8, queue 1000, named threads). Notification types: `BOOKING_CONFIRMED`, `BOOKING_CANCELLED`,
`REFUND_COMPLETED`, `SHOW_REMINDER`. Mock delivery is acceptable.

Reminders via `@Scheduled`, finding upcoming confirmed bookings. **Reminder delivery must be idempotent.**

If a durable outbox is implemented, use PostgreSQL as a transactional outbox with
`FOR UPDATE SKIP LOCKED`, not a separate broker — and explain the choice in the README.

## 12. Testing

- **Unit**: `PricingService`, `DiscountService`, `RefundService`, booking validation, payment logic, hold
  expiration logic.
- **Repository/integration**: Flyway migrations, PostgreSQL constraints, repository queries, pessimistic
  seat locking.
- **Controller integration**: authentication, authorization, validation, correct status codes.
- **Concurrency**: multiple concurrent users attempting to hold the same seat — e.g. 20 attempts, with
  exactly one successful allocation, all others failing safely, no duplicate booking rows, and the seat
  left in a valid state. Use `CountDownLatch` / `ExecutorService` / `CompletableFuture` or Java 21 virtual
  threads.

**The test must run against PostgreSQL using Testcontainers rather than relying solely on H2.**

Seed data must demonstrate at least 2 cities, multiple theaters, screens, seats and shows, regular and
premium pricing, weekend pricing, discount codes, refund policies, an admin user and customer users.

## 13. Configuration and code quality

`application.properties` plus `application-test.properties`. Externalise the datasource, hold duration,
scheduler interval, notification executor settings, payment mock settings and refund configuration.
**Never hard-code database credentials in Java.** A small `docker-compose.yml` for local PostgreSQL.

Code quality: clear names, small focused methods, constructor injection, `final` fields, enums instead of
magic strings, `BigDecimal` for money, `Instant`/`OffsetDateTime` for timestamps, no business logic in
controllers, no giant service classes, no duplicated validation, **no unnecessary abstractions and no
design-pattern showcase**. Patterns only where they genuinely improve the design — Strategy for pricing,
refund policies and the payment gateway; Factory only where multiple implementations genuinely need
construction.

Additional conventions:

- a single endpoint-constants class, with controllers referencing those constants;
- explicit DAO-level column definitions, e.g.
  `@Column(columnDefinition = "VARCHAR(255)", name = "username")`;
- a considered decision between UUID and auto-increment primary keys;
- consistent formatting per the supplied IntelliJ code style.

## 14. Build order

1. project setup, configuration, database, Flyway, common error model
2. users, security, RBAC
3. cities, theaters, screens, seats, shows
4. show-seat inventory
5. pricing, discounts
6. seat hold and concurrency
7. payment
8. booking confirmation
9. cancellation and refunds
10. async notifications
11. reminders
12. unit, integration and concurrency tests
13. README and AI documentation

## 15. Quality gate

Before the implementation is considered complete, verify that: the application starts; Flyway migrations
run; repositories work against PostgreSQL; all required REST APIs work; admin/customer authorization
works; validation works; seat holds expire correctly; **expired holds can be reused even if the scheduler
is late**; **concurrent booking does not double-allocate**; payment failure releases seats; confirmed
bookings cannot be double-confirmed; cancellation respects the refund policy; refunds are persisted;
notifications do not block booking; reminders are idempotent; tests pass; integration tests use
PostgreSQL; the README is complete; `AGENTS.md`/`CLAUDE.md` exist; and the AI documentation exists.

Run `mvn clean verify` and fix all failures before declaring completion.

## 16. Working instructions

Implement the files — do not stop at architecture, do not leave TODOs for core functionality, and do not
invent requirements that conflict with this specification. **Where there is a genuine design ambiguity,
ask rather than assume**; where it is obvious, choose a reasonable implementation and document the
assumption in the README. Do not replace PostgreSQL locking with in-memory locking. Do not replace the
concurrency test with a mock. Do not claim a feature is implemented unless the code and tests actually
implement it.
