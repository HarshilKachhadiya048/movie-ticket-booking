# Movie Ticket Booking System

A seat-level movie ticket booking platform built as a modular monolith on Java 21 and Spring Boot 4.

The interesting problem here is not CRUD. It is that **two customers must never both be told they have
the same seat**, while the system also has to cope with time-bound holds, a payment gateway that can be
slow or fail, configurable pricing and refunds, and notifications that must not make anyone wait. This
README explains how each of those is solved and, more importantly, *why* that solution rather than
another.

```
mvn clean verify     ->  106 unit tests + 109 integration tests, ~45s
```

---

## Table of contents

1. [Problem statement](#1-problem-statement)
2. [Scope](#2-scope)
3. [Assumptions](#3-assumptions)
4. [Architecture](#4-architecture)
5. [Why PostgreSQL](#5-why-postgresql)
6. [Concurrency strategy](#6-concurrency-strategy) — *the core of the design*
7. [Why pessimistic locking](#7-why-pessimistic-locking)
8. [Why no Redis](#8-why-no-redis)
9. [Why no SQS or Kafka](#9-why-no-sqs-or-kafka)
10. [Booking workflow](#10-booking-workflow)
11. [Hold expiry workflow](#11-hold-expiry-workflow)
12. [Payment workflow](#12-payment-workflow)
13. [Cancellation and refund workflow](#13-cancellation-and-refund-workflow)
14. [Async notification workflow](#14-async-notification-workflow)
15. [Database schema](#15-database-schema)
16. [API reference](#16-api-reference)
17. [Authentication](#17-authentication)
18. [Local setup](#18-local-setup)
19. [Running the application](#19-running-the-application)
20. [Running the tests](#20-running-the-tests)
21. [The concurrency test](#21-the-concurrency-test)
22. [Trade-offs and known limitations](#22-trade-offs-and-known-limitations)
23. [Future improvements](#23-future-improvements)

---

## 1. Problem statement

Customers browse cities, theaters and shows, pick specific seats, hold them for a few minutes, pay, and
receive a confirmation. They can cancel and get a refund according to a configurable policy.
Administrators manage the catalogue, the seat layouts, the schedule, the prices, the discount codes and
the refund policies.

The hard requirements:

| Requirement | Where it is solved |
|---|---|
| Two users can never allocate the same seat | [§6](#6-concurrency-strategy) — PostgreSQL `SELECT … FOR UPDATE` |
| Holds expire, and expiry must not depend on a scheduler | [§11](#11-hold-expiry-workflow) — lazy expiry under the row lock |
| No DB transaction open across a payment call | [§12](#12-payment-workflow) — two transactions, gateway between them |
| Pricing and refund policy are configuration, not code | [§15](#15-database-schema) — `pricing_rules`, `refund_policy_rules` |
| Notifications must not block booking | [§14](#14-async-notification-workflow) — transactional write, after-commit dispatch |
| Discount usage limits cannot be raced past | [§6](#6-concurrency-strategy) — row lock on the code |

---

## 2. Scope

**In scope:** REST APIs, relational persistence with Flyway migrations, role-based access control, input
validation, centralised error handling, seat-level concurrency control, hold expiry, pricing tiers with
weekend rates, discount codes, a mock payment gateway, configurable refund policies, asynchronous
notifications, pre-show reminders, and unit / integration / concurrency tests.

**Out of scope for now**: any frontend, deployment, CI/CD, microservices, distributed-system
architecture, OAuth / SSO / MFA, and production-grade observability. A `Dockerfile` and
`docker-compose.yml` are included purely so the project can be run locally in one command — they are not
a deployment story.

---

## 3. Assumptions

Every judgement call made while scoping the system, and why.

| # | Assumption | Reasoning |
|---|---|---|
| 1 | **A `movies` table exists** as its own entity. | A show is a screening *of something*, and the listing has to say what. The alternative — copying title, language and runtime onto every show row — is strictly worse. |
| 2 | **Cities carry an IANA time zone.** | "Weekend pricing" is a question about the local calendar, not UTC. A 00:30 Saturday show in Mumbai is 19:00 Friday in UTC; pricing it off UTC would silently undercharge. The seed data includes Dubai specifically so this is observable. |
| 3 | **Catalogue browsing requires authentication.** | Browsing is a customer capability. Any authenticated role may browse; only customers may book. Making the catalogue public is one line in `SecurityConfig`. |
| 4 | **A hold is all-or-nothing.** | Partially fulfilling a four-seat request would leave a customer paying for a fragment of what they asked for. |
| 5 | **A seat already held by *you* still blocks a new hold**, with a distinct message. | Silently releasing your own earlier hold would invalidate that other booking without telling you. |
| 6 | **`PAYMENT_FAILED` is terminal.** | A declined payment releases the seats, so there is nothing left to retry against — they may already belong to somebody else. Retrying means taking a fresh hold, which is honest about what actually has to happen. |
| 7 | **`CANCELLED` and `REFUNDED` are distinct terminal states.** | Both mean cancelled. `REFUNDED` additionally means money went back. The `refunds` row carries the detail. |
| 8 | **`EXPIRED` is a distinct state.** | Splitting out "the hold simply lapsed" keeps *the customer changed their mind* separate from *the customer never paid*. |
| 9 | **A discount is applied at hold time**, and its redemption is reserved then. | The customer sees the final payable amount before paying, and cannot have their code taken by somebody else mid-payment. The reservation is released if the booking does not complete. |
| 10 | **Currency is a single platform-wide setting** (`booking.currency`). | Multi-currency is a genuinely large feature (FX, rounding rules, per-market pricing) and out of scope for now. The Dubai seed city is therefore priced in INR, which is unrealistic but harmless — see [§22](#22-trade-offs-and-known-limitations). |
| 11 | **A seat layout cannot be edited once created.** | Every show on the screen has already materialised inventory from it. Changing it is a data migration, not an API call. |
| 12 | **A show with confirmed bookings cannot be cancelled by an admin.** | Cancelling would mean issuing refunds for all of them — a deliberate money-moving operation, not a side effect of a status toggle. |
| 13 | **Ownership failures report 403, not 404.** | Both are defensible. The caller already authenticated and would have to guess a UUIDv7; hiding existence buys nothing the id space does not already provide. |
| 14 | **Business-rule violations report 422**, distinct from 409 for conflicts over shared state. | "Your discount code expired" is a different class of problem from "somebody else took that seat". See [§16](#16-api-reference). |

---

## 4. Architecture

A **modular monolith organised by business domain** — not by technical layer. Each package owns its
entities, repository, services, DTOs and controllers:

```
com.harshil.movieticketbooking
├── common          BaseEntity, Money, ErrorCode, ApiEndpoints, GlobalExceptionHandler, config
├── security        SecurityConfig, AppUserDetails, REST-shaped auth error handlers
├── user            accounts and roles
├── city            cities (and their time zones)
├── movie           films
├── theater         theaters and screens
├── seat            physical seat layouts
├── show            shows and show_seat inventory        <- the contended table
├── pricing         pricing rules and resolution
├── discount        discount codes and redemption
├── booking         holds, payment orchestration, cancellation, sweeper
├── payment         PaymentGateway abstraction + mock implementation
├── refund          refund policies, calculation, issued refunds
└── notification    transactional queueing, async dispatch, reminders
```

**Rules the codebase follows:**

- Controllers contain no business logic. They bind, validate, supply the authenticated user id, delegate.
- Entities never leave a transaction. Services return DTOs assembled while the session is open, so a lazy
  association can never be touched after commit.
- Cross-domain access goes through the owning service, never another domain's repository.
- Every HTTP path is a constant in `ApiEndpoints`, referenced exactly once. No class-level
  `@RequestMapping`; each handler carries its full path. The same constants are reused by `SecurityConfig`
  and by the tests, so a path cannot drift between routing, security rules and tests.
- Time is read only from an injected `Clock`. No `Instant.now()` anywhere in production code — which is
  what lets tests assert "the hold expired" without sleeping.
- Money is `BigDecimal`, normalised to scale 2 *before* arithmetic (`Money`). This is not cosmetic: see
  [§15](#15-database-schema).

**Patterns are used only where they pay for themselves.** `PaymentGateway` is an interface because there
are genuinely two implementations in play (mock now, real later) and because it marks the transaction
boundary. `DiscountType` puts its arithmetic on the enum constant rather than in a switch. `PricingScope`
ranks specificity. There is no factory, no visitor and no abstract base service, because none would
improve anything.

---

## 5. Why PostgreSQL

The whole correctness argument of this system is *"the database serialises access to the contended row"*.
That requires a database that actually does:

- `SELECT … FOR UPDATE` with real blocking semantics, under MVCC, so readers are not blocked by writers;
- `FOR UPDATE … SKIP LOCKED`, used by the reminder sweep so multiple instances claim disjoint batches;
- partial indexes — `UNIQUE (booking_id) WHERE status = 'SUCCESS'` is what makes "a booking cannot be
  double-charged" a *structural* guarantee rather than a hopeful one;
- `UNIQUE NULLS NOT DISTINCT` (PostgreSQL 15+), which deduplicates the global pricing rules where all
  scope columns are NULL — standard SQL would treat those NULLs as distinct and let duplicates through;
- `TIMESTAMPTZ` and a native `uuid` type.

Every one of those is load-bearing. This is also why the tests run against real PostgreSQL via
Testcontainers rather than H2 — see [§20](#20-running-the-tests).

---

## 6. Concurrency strategy

**The single most important section of this document.**

### The guarantee

Two customers can never both be told they have the same seat for the same show. This is enforced by
PostgreSQL row locks, not by application coordination.

### The mechanism

Seat inventory lives in `show_seats`: exactly one row per `(show, seat)`, enforced by
`uq_show_seats_show_seat`. That unique constraint means duplicate allocation is not merely prevented —
it is **unrepresentable**.

The hold transaction, in `SeatHoldService`:

```java
@Transactional
public BookingResponse createHold(CreateHoldCommand command) {
    List<UUID> seatIds = normalizeSeatIds(command.seatIds());   // dedupe, bound, SORT
    Show show = loadShowWithVenue(...);                         // validate bookable
    Map<UUID, Seat> seats = loadSeats(seatIds, show);           // reference data, no lock needed

    // ---- everything below runs while the rows are locked ----
    List<ShowSeat> locked = showSeatRepository.lockByShowAndSeatIds(show.getId(), seatIds);
    validateAllSeatsBelongToShow(locked, seatIds, show);
    validateAllSeatsAvailable(locked, seats, now, command.userId());   // lazy expiry applied here

    PriceQuote quote = pricingService.quote(show, orderedSeats(...));
    Optional<DiscountApplication> discount = discountService.evaluate(...);  // locks the code, after seats

    Booking booking = persistBooking(...);
    discount.ifPresent(d -> discountService.recordRedemption(d, userId, booking.getId()));
    applyHold(locked, booking, ...);                            // status -> HELD, token, expiry
    // ---- locks released on commit ----
}
```

The repository method is the whole trick:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
        SELECT ss FROM ShowSeat ss
        WHERE ss.show.id = :showId AND ss.seat.id IN :seatIds
        ORDER BY ss.seat.id
        """)
List<ShowSeat> lockByShowAndSeatIds(UUID showId, Collection<UUID> seatIds);
```

Hibernate renders this as `SELECT … FOR UPDATE`. The second transaction to ask for a contended row
**blocks inside PostgreSQL** until the first commits or rolls back, then resumes and reads the committed
state — so it sees `HELD` and is rejected with 409. There is no window between checking and mutating,
because both happen under the same lock in the same transaction.

### Why locking is narrow

`ss.show.id` and `ss.seat.id` resolve to foreign-key columns, so no join is emitted and the query is a
single-table select. Only the specific inventory rows are locked — never the `shows`, `screens` or `seats`
tables. Bookings for different seats of the same show proceed fully in parallel, which
`ShowSeatLockingIT.differentRowsDoNotBlockEachOther` asserts directly.

### Deadlock avoidance

Two overlapping multi-seat requests are the dangerous case: A wants seats {1,2}, B wants {2,1}. If each
locked in caller order they could hold what the other needs.

Two things prevent it:

1. `normalizeSeatIds` **sorts** the requested ids before locking.
2. The query carries `ORDER BY ss.seat.id`, and PostgreSQL places its `LockRows` plan node above `Sort`,
   so rows are locked in sorted order.

Every transaction therefore walks contended rows in the same sequence. A deadlock would still be reported
cleanly as 409 (`CannotAcquireLockException` → `CONCURRENT_MODIFICATION`) rather than corrupting anything,
but with consistent ordering it should not arise.

There is a second ordering rule: the discount code row is always locked **after** the seats, never before.
A fixed global lock order across all resources is what keeps that safe.

### Defence in depth

| Layer | What it catches |
|---|---|
| `SELECT … FOR UPDATE` | The real mechanism. Serialises contending transactions. |
| `uq_show_seats_show_seat` | Makes duplicate inventory unrepresentable. |
| `@Version` on `show_seats` | Any path that mutates the row *without* taking the lock fails loudly instead of silently overwriting. |
| `ck_show_seats_hold_fields` | A half-released seat (status changed but token left behind) is rejected by the database. |

That third row is not theoretical. When the pessimistic lock was deliberately removed to check the
concurrency test could actually fail (see [§21](#21-the-concurrency-test)), the optimistic version check
is what caught the race — turning a silent double-allocation into a loud `ObjectOptimisticLockingFailure`.

### Discount usage limits

The same problem in miniature: reading "99 of 100 used" and then incrementing is a read-modify-write race
where two customers can both read 99. `DiscountService.evaluate` loads the code with
`SELECT … FOR UPDATE`, so concurrent redemptions of one code serialise inside PostgreSQL, and the
per-user count read under that lock is safe too.

One subtlety worth calling out: the code is resolved to an id *first*
(`DiscountCodeRepository.findIdByCode`, a scalar projection) and only then loaded by the locking query.
Loading the entity first and locking it afterwards would leave the already-managed instance in the
persistence context, and Hibernate returns *that* rather than refreshing from the locking select — so the
limit check could run against a stale counter.

### What is deliberately *not* used

| Rejected | Why |
|---|---|
| `synchronized` / `ReentrantLock` | Coordinates one JVM. Two instances, or one restart, and the guarantee is gone. |
| Redis / distributed lock | A second source of truth that can disagree with the database. See [§8](#8-why-no-redis). |
| `SERIALIZABLE` isolation | Would push the cost onto every transaction in the system and produce serialization failures that all need retry logic — to solve a problem confined to one table. `READ COMMITTED` + explicit row locks is precise and cheap. |
| Optimistic locking alone | Would work, but every loser fails at *commit* after doing all the pricing and discount work, and reports a confusing "please retry" rather than an honest "that seat is taken". |

---

## 7. Why pessimistic locking

Optimistic locking is usually the right default: it costs nothing when there is no contention.

Seat booking is precisely the case where that reasoning inverts. **Contention is the expected state**, not
the exception — a popular show's front-row seats are exactly what everyone clicks at once. With optimistic
locking every loser does the full amount of work (validate, price, evaluate discount, build the booking)
and only discovers at commit that it wasted it. Under a burst that becomes a retry storm, and the user
sees "something changed, please try again" instead of "that seat is taken".

With a pessimistic lock the loser waits a few milliseconds, then reads the committed truth and gets a
clean, accurate `409 SEAT_ALREADY_HELD` naming the seats. The transaction is short and touches nothing
but database rows, so holding the lock is cheap. Crucially, **no gateway call, HTTP request or
notification happens while it is held** — see [§12](#12-payment-workflow).

---

## 8. Why no Redis

Redis is the standard reflex for seat holds, and it is the wrong tool here.

- **It would be a second source of truth.** The seat's real state lives in PostgreSQL, because that is
  what the booking, the payment and the refund are joined to. A hold in Redis and a booking in Postgres
  can disagree — after a Redis eviction, a failover, or simply a partial failure between the two writes.
  Reconciling them is a distributed-transaction problem invented entirely by the choice of Redis.
- **PostgreSQL already provides it.** Row-level locking with MVCC is exactly this feature, transactional
  with the data it protects, at no extra cost.
- **Hold expiry does not need a TTL.** `hold_expires_at` is a column, evaluated under the lock. A Redis
  TTL would expire the hold but leave the booking row stale, so the reconciliation would be needed anyway.
- **It adds an operational dependency** whose failure mode is "seats cannot be booked" — for a monolith
  that is deliberately not a distributed system.

Redis would earn its place if seat-map *reads* became a bottleneck, which is a caching problem, not a
correctness one. See [§23](#23-future-improvements).

---

## 9. Why no SQS or Kafka

The requirement is that booking confirmation must not block the request. That needs asynchrony, not a
broker.

- **A broker does not solve the hard part.** The genuinely difficult bit is atomicity: "the booking is
  confirmed" and "a confirmation is owed" must commit together. Publishing to Kafka *from* a database
  transaction reintroduces the dual-write problem — the classic reason people then add an outbox.
- **The database already gives atomicity.** A `notifications` row written in the same transaction as the
  booking commits or rolls back with it. That is the property a broker would have to work to preserve.
- **It would add an operational dependency** for a single-instance monolith with modest volume.

What is implemented instead: the notification row is written transactionally, then dispatched after commit
on a bounded executor. Nothing is lost if delivery fails — the row is still there. See
[§14](#14-async-notification-workflow), including the extension path to a durable outbox.

---

## 10. Booking workflow

```
   browse                    hold                      pay                    attend
 ┌─────────┐        ┌──────────────────┐      ┌──────────────────┐
 │ cities  │        │ TX: lock seats,  │      │ TX1: lock, write │
 │ shows   │──────▶ │ price, discount, │────▶ │      PENDING     │
 │ seats   │        │ create booking   │      │ ---- gateway ----│
 └─────────┘        └──────────────────┘      │ TX2: confirm     │
                       HOLD_CREATED           └──────────────────┘
                       5 min expiry                CONFIRMED
```

1. `GET /shows/{id}/seats` returns the seat map with **effective** availability (a lapsed hold reads as
   available, matching exactly what the booking path would decide) and the resolved price per seat.
2. `POST /holds` runs the locked transaction of [§6](#6-concurrency-strategy) and returns a booking in
   `HOLD_CREATED` with the full price breakdown and `holdExpiresAt`.
3. `POST /bookings/{id}/payment` runs the two-transaction flow of [§12](#12-payment-workflow).
4. A `BOOKING_CONFIRMED` notification is queued in the confirming transaction and delivered after commit.

### State machine

```
                  payment initiated            gateway approved
 HOLD_CREATED ──────────────────────▶ PAYMENT_PENDING ───────────────▶ CONFIRMED
      │                                      │                              │
      │ cancelled / lapsed                   │ declined                     │ cancelled
      ▼                                      ▼                              ▼
 CANCELLED | EXPIRED                   PAYMENT_FAILED           CANCELLED | REFUNDED
```

Enforced by `Booking.transitionTo`, which throws `INVALID_BOOKING_STATE_TRANSITION` (409) for any move
not in the graph. Re-applying the current state is a no-op, which is what makes the payment and
cancellation paths safely retryable.

---

## 11. Hold expiry workflow

A scheduler must not be the only thing that makes an expired seat bookable. Here it is not what makes
it bookable **at all**.

### A. Lazy expiry — the actual mechanism

```java
public ShowSeatStatus effectiveStatus(Instant now) {
    if (status == HELD && isHoldExpired(now)) {
        return AVAILABLE;
    }
    return status;
}
```

Evaluated inside the hold transaction, **while the row is locked**. A row still marked `HELD` whose
`hold_expires_at` has passed is treated as available and taken over immediately. Correctness therefore
never depends on the sweeper having run.

`HoldExpiryIT.expiredHoldsAreReusableWithoutTheScheduler` asserts precisely this: the scheduler is
disabled in the test profile, the stored row is asserted to still read `HELD`, and the seat is
nonetheless successfully re-booked.

### B. Scheduled sweep — tidying only

`ExpiredHoldSweeper` runs on a fixed delay and exists so that:

- seat maps and availability counts read naturally, without every query reasoning about expiry;
- abandoned bookings reach the terminal `EXPIRED` state instead of sitting in `HOLD_CREATED` forever;
- discount codes reserved by an abandoned hold return to circulation.

That last one is the only user-visible consequence of the sweep not running, and it is bounded — the
redemption is also released on cancellation and on payment failure.

### The subtle safety rule

Release is guarded by the **hold token**, never by status alone:

```java
public boolean isHeldUnder(UUID token) {
    return status == HELD && token != null && token.equals(holdToken);
}
```

Once a hold lapses, another customer can legitimately take the seat through lazy expiry — and their hold
carries a *different* token. A sweeper releasing on status would take that seat away from someone holding
it perfectly legitimately, and they would arrive at the cinema without a seat.
`HoldExpiryIT.theSweeperDoesNotStealASeatReclaimedByAnotherCustomer` covers exactly this sequence.

---

## 12. Payment workflow

**No database transaction is ever open across the gateway call.** This is structural, not a convention:

```
 ┌── transaction 1 ─────────────────────────────┐
 │ lock booking (FOR UPDATE)                    │
 │ check idempotency key                        │
 │ check state and hold expiry                  │
 │ INSERT payment (PENDING)                     │
 │ booking -> PAYMENT_PENDING                   │
 └── COMMIT ────────────────────────────────────┘
        │
        │   ✦ no transaction, no locks held ✦
        ▼
    paymentGateway.charge(...)
        │
 ┌── transaction 2 ─────────────────────────────┐
 │ lock booking and its show_seats              │
 │ re-verify the hold token under the lock      │
 │ success -> seats BOOKED, booking CONFIRMED   │
 │ decline -> seats released, PAYMENT_FAILED    │
 │ queue notification                           │
 └── COMMIT ────────────────────────────────────┘
```

### Why the orchestration lives in a separate bean

`BookingPaymentService` holds **no** `@Transactional` annotation; it delegates to
`BookingPaymentTransactionService`. Spring's `@Transactional` works through a proxy, so a method calling
another method on `this` bypasses it entirely. Putting the orchestration and the transactional steps in
one class would silently produce **one long transaction spanning the gateway call**. Splitting the beans
makes the boundary real and impossible to lose by accident.

### Why failures are returned, not thrown

"The payment was declined, the seats are released, the booking is terminal" is a state that must be
**persisted**. Throwing from inside the transaction that records it would roll it back and leave the
booking stuck in `PAYMENT_PENDING`, still holding seats it will never pay for. So `settle()` returns a
`PaymentSettlement` and the orchestrator raises the HTTP error after the commit.

*This was a real bug caught by the tests.* `prepare()` originally expired the booking, released its seats,
and then threw `HOLD_EXPIRED` — rolling back the very cleanup it had just done.
`HoldExpiryIT.payingAfterTheHoldLapsedIsRejectedAndTheSeatsAreFreed` failed with the booking still in
`HOLD_CREATED`, and the fix was to add `PaymentPreparation.Kind.HOLD_EXPIRED` as a returned outcome.

### Three outcomes, three different meanings

| Gateway result | Response | What happens |
|---|---|---|
| Approved | 200 | Seats `BOOKED`, booking `CONFIRMED`, payment `SUCCESS`. |
| Declined | **402** | Seats released, booking `PAYMENT_FAILED`, payment `FAILED`. A decline is a definite "no". |
| Transport failure | **502** | Payment stays **`PENDING`**. The outcome is *unknown* — the charge may have happened and the response been lost. Asserting "not charged" would be a guess. The hold lapses normally and the row remains for reconciliation. |

### The awkward case, handled honestly

Time passes between the two transactions. The hold can lapse while the gateway is being called — and then
the charge succeeds but the seats are gone. Pretending otherwise would mean taking a customer's money and
giving them nothing.

`settle()` detects it (the locked rows no longer carry the booking's hold token), records the payment as
`SUCCESS` *because money genuinely moved*, expires the booking, and returns
`HOLD_LAPSED_AFTER_CHARGE`. The orchestrator then issues a compensating refund through the gateway —
again outside any transaction — and records it as a normal `refunds` row. The customer gets
`409 HOLD_EXPIRED` and their money back.

### Idempotency

Every payment request carries a client-supplied `idempotencyKey`, unique in the database.

| Existing payment with that key | Behaviour |
|---|---|
| `SUCCESS` | Replays the original result. No second charge. |
| `FAILED` | Replays the decline (402). |
| `PENDING` | 409 — another request holds this key and has not settled. |
| Different booking | 400 — the key was reused. |

Backed by `uq_payments_idempotency_key` and, independently, by the partial unique index
`ux_payments_booking_successful` which makes two successful payments for one booking impossible even if
the application logic were wrong. `SchemaMigrationIT` asserts that with raw SQL.

---

## 13. Cancellation and refund workflow

Same two-transaction shape, same reason.

```
 ┌── transaction 1 ─────────────────────────────┐
 │ lock booking; already cancelled? replay      │
 │ validate ownership and cancellation window   │
 │ resolve policy -> assess refund              │
 │ INSERT refund (PENDING)                      │
 │ release seats; release discount redemption   │
 │ booking -> REFUNDED (or CANCELLED if 0%)     │
 │ queue BOOKING_CANCELLED                      │
 └── COMMIT ────────────────────────────────────┘
        │  ✦ gateway refund, no locks held ✦
 ┌── transaction 2 ─────────────────────────────┐
 │ refund -> COMPLETED / FAILED                 │
 │ queue REFUND_COMPLETED                       │
 └── COMMIT ────────────────────────────────────┘
```

**Ordering is deliberate.** The cancellation commits and the seats go back on sale *before* the payment
provider is contacted. A refund the gateway rejects is left as a `FAILED` row for operational follow-up —
the alternative, unwinding the cancellation, would tell a customer who asked to cancel that they are still
booked because a third party had an outage.

### Configurable policies

Nothing about the ladder is in Java. A `RefundPolicy` is a named set of **half-open** hour bands:

| Band | Refund |
|---|---|
| `[24, ∞)` | 100% |
| `[12, 24)` | 50% |
| `[0, 12)` | 0% |

Half-open intervals mean adjacent bands share an endpoint without overlapping or leaving a gap, so a
cancellation at exactly 24 hours has precisely one answer. Overlapping bands are rejected at creation with
409 — otherwise the same cancellation could yield 50% or 100% depending on row order.

Resolution: the show's theater policy if it has one, otherwise the single platform default (both enforced
by partial unique indexes). **It fails closed** — no policy, or a gap in the ladder, yields 0%, never
100%. Under-refunding visibly is better than giving money away silently.

Hours are computed from *seconds*, not rounded to whole hours, so 23h59m stays in the 50% band.

Idempotency comes from three places: the booking row lock serialises concurrent attempts, the status check
short-circuits, and `uq_refunds_booking` makes a second refund row impossible in principle.

---

## 14. Async notification workflow

```
 ┌── business transaction ──────────────────┐
 │  ... confirm the booking ...             │
 │  INSERT notification (PENDING, dedupe)   │   atomic with the booking
 │  publishEvent(NotificationQueuedEvent)   │
 └── COMMIT ────────────────────────────────┘
        │
        │  @TransactionalEventListener(AFTER_COMMIT)
        │  @Async("notificationExecutor")          <- HTTP thread returns here
        ▼
   NotificationDispatcher.dispatch(id)   ─▶  NotificationSender  ─▶  SENT / FAILED
```

**Why the row is written at all.** Writing it inside the business transaction means "the booking is
confirmed" and "a confirmation is owed" commit or roll back together. A rolled-back booking cannot leave a
confirmation behind; a committed booking cannot silently lose one.

**`AFTER_COMMIT`, not `@EventListener`.** A plain listener fires inside the transaction and could email a
customer about a booking that then rolled back.

**Deduplication.** Each notification has a natural key — `BOOKING_CONFIRMED:<bookingId>` — under a unique
index. A scheduler that runs twice, a retried dispatch, or a second instance all collapse to one
notification, without a distributed lock.

**The executor is bounded on purpose**, and its rejection policy is neither stock option:

- `CallerRunsPolicy` would run delivery on the caller's thread — which, for an after-commit listener, is
  the HTTP request thread — exactly what this executor exists to avoid.
- `AbortPolicy` throws `TaskRejectedException` while submitting from inside an after-commit callback,
  which would surface to the client as a **500 on a booking that actually succeeded and was committed**.

So the handler logs and discards. The request stays correct, and the undelivered row is still on disk as
`PENDING`.

`NotificationIT.deliveryRunsOffTheCallingThread` asserts the send happened on a thread named
`notification-*` and not the caller's — which is the only way to actually prove the request was not
blocked. A live run confirms the same:

```
[ notification-1] LoggingNotificationSender : [BOOKING_CONFIRMED] to aisha@example.com via EMAIL | ...
[ notification-2] LoggingNotificationSender : [BOOKING_CANCELLED] ...
[ notification-3] LoggingNotificationSender : [REFUND_COMPLETED]  ...
```

### Reminders

`ShowReminderService` sweeps for confirmed bookings whose show starts within the lead time. Idempotent
three times over:

1. the claim selects only `reminder_sent_at IS NULL` and marks them in the same transaction;
2. the claim uses **`FOR UPDATE OF b SKIP LOCKED`**, so a second instance takes a different batch rather
   than blocking or duplicating;
3. the notification's unique `dedupe_key` means only one row can exist regardless.

Layer three is the actual guarantee; the first two just stop the work being done twice.

### Extending to a durable outbox

**Not implemented** — but the design is deliberately shaped so that adding it is purely additive, with
no rewrite and no data migration:

- `notifications` already carries `status`, `attempt_count` and `available_at`, and already has the
  partial index `ix_notifications_pending ON (available_at) WHERE status = 'PENDING'`;
- the row is already written transactionally, which is the hard half of the outbox pattern;
- `NotificationDispatcher.dispatch(UUID)` is already the single delivery entry point.

Adding durability means **one new class**: a `@Scheduled` poller that claims `PENDING` rows with
`FOR UPDATE SKIP LOCKED` and calls that same `dispatch`. Nothing above it changes. The existing
`claimBookingsDueForReminder` query is a working example of exactly that claim pattern.

---

## 15. Database schema

18 tables across 6 versioned migrations, split along domain lines. Demo data lives separately in
`db/seed` (see [§18](#18-local-setup)).

| Migration | Tables |
|---|---|
| `V1__core_reference_schema` | `users`, `cities`, `movies`, `theaters`, `screens`, `seats` |
| `V2__show_and_inventory_schema` | `shows`, **`show_seats`** |
| `V3__pricing_and_discount_schema` | `pricing_rules`, `discount_codes` |
| `V4__booking_and_payment_schema` | `bookings`, `booking_seats`, `discount_code_usages`, `payments` |
| `V5__refund_schema` | `refund_policies`, `refund_policy_rules`, `refunds` |
| `V6__notification_schema` | `notifications` |

```
cities ──< theaters ──< screens ──< seats
   │                       │           │
   │                       └──< shows ─┴──< show_seats ◀── the contended table
   │                                          │
users ──< bookings ──< booking_seats ─────────┘
            │  │
            │  └──< payments ──< refunds >── refund_policies ──< refund_policy_rules
            └──< discount_code_usages >── discount_codes
```

### Why UUIDv7 primary keys

UUIDs keep ids non-enumerable and generatable without a database round trip.
Plain UUIDv4 pays for that with random insert positions: every new row lands on an arbitrary B-tree page,
fragmenting the index and bloating the WAL.

**UUIDv7 embeds a millisecond timestamp in its high bits**, so generated ids increase monotonically and
inserts stay at the right-hand edge of the index the way a sequence would — while remaining genuine UUIDs.
That matters most for `show_seats`, the one table this system writes to under real concurrent pressure.

Generated by Hibernate 7:

```java
@Id
@UuidGenerator(algorithm = UuidVersion7Strategy.class)
@Column(columnDefinition = "UUID", name = "id", nullable = false, updatable = false)
private UUID id;
```

The seed data generates v7-shaped ids too, via a small SQL helper that is dropped at the end of the
migration, so seed rows are not obvious outliers.

### Constraints that carry real weight

These are not decoration — each makes an application-level guarantee structural. All are asserted with
raw SQL in `SchemaMigrationIT`.

| Constraint | Guarantees |
|---|---|
| `uq_show_seats_show_seat` | Duplicate seat allocation is unrepresentable. |
| `ck_show_seats_hold_fields` | A row is either cleanly HELD (token + holder + expiry) or has no hold state at all. A half-released seat cannot exist. |
| `ux_payments_booking_successful` *(partial)* | At most one `SUCCESS` payment per booking. Double-confirmation is impossible. |
| `uq_payments_idempotency_key` | A retried request cannot charge twice. |
| `uq_refunds_booking` | Cancellation is idempotent at the storage layer. |
| `uq_notifications_dedupe_key` | Exactly one notification per event, whatever the scheduler does. |
| `ck_bookings_total_is_net` | `total = subtotal - discount`, always. |
| `uq_pricing_rules_scope` *(NULLS NOT DISTINCT)* | No duplicate rule per scope — including the global rules where all scope columns are NULL. |
| `ux_refund_policies_single_default` *(partial on `(TRUE)`)* | Exactly one platform default policy. |
| `ck_pricing_rules_single_scope` | A rule is scoped at one level, never two. |

### Why money is normalised before arithmetic

`ck_bookings_total_is_net` is why `Money` rounds to scale 2 *before* computing, not after. PostgreSQL
rounds on store: a discount of `10.005` becomes `10.01`, but a total computed from the unrounded value
would be `89.995 → 90.00`, and `100.00 - 10.01 ≠ 90.00` would violate the constraint.
`MoneyTest.subtractMatchesStoredValues` pins this exact case.

### Indexes

Beyond the constraints above, the hot paths are covered by `ix_show_seats_show_status` (seat maps),
`ix_show_seats_hold_expiry` — **partial**, `WHERE status = 'HELD'`, so the sweeper never scans the whole
table — `ix_bookings_user_created_at` (history), and `ix_bookings_reminder_pending` — partial on
`status = 'CONFIRMED' AND reminder_sent_at IS NULL`, keeping the reminder working set tiny.

---

## 16. API reference

Base path `/api/v1`. All endpoints require HTTP Basic except registration. JSON in, JSON out.

### Status code conventions

| Code | Meaning |
|---|---|
| 400 | Malformed request, or bean validation failed. |
| 401 | Not authenticated. |
| 402 | Payment declined by the gateway. |
| 403 | Authenticated but not permitted — including *"this booking is not yours"*. |
| 404 | The addressed resource does not exist. |
| **409** | Conflict over shared mutable state: seat availability, concurrency, booking lifecycle, duplicates. |
| **422** | Well-formed, but violates a configured business rule (discount limits, refund policy bands). |
| 502 | The payment gateway could not be reached. |

### Error body

Every failure — including ones raised inside the security filter chain — returns this shape:

```json
{
  "timestamp": "2026-09-28T11:23:13.543Z",
  "status": 409,
  "error": "Conflict",
  "code": "SEAT_ALREADY_HELD",
  "message": "Seat(s) A2, A1 are currently held by another customer",
  "path": "/api/v1/holds",
  "details": { "heldSeats": ["A2", "A1"] }
}
```

Validation failures add a `fieldErrors` array. Stack traces, SQL and constraint names are **never**
returned; they are logged server-side only.

### Customer endpoints

| Method | Path | Auth | Notes |
|---|---|---|---|
| `POST` | `/auth/register` | public | Always creates a `CUSTOMER`. → 201 |
| `GET` | `/auth/me` | any | The authenticated principal. |
| `GET` | `/cities` | any | Active cities with their time zones. |
| `GET` | `/cities/{cityId}` | any | |
| `GET` | `/cities/{cityId}/theaters` | any | |
| `GET` | `/theaters/{theaterId}` | any | |
| `GET` | `/theaters/{theaterId}/shows` | any | `?from=&to=` ISO-8601; defaults to the next 7 days. Includes live seat counts. |
| `GET` | `/shows/{showId}` | any | |
| `GET` | `/shows/{showId}/seats` | any | Seat map with **effective** status and per-seat price. |
| `GET` | `/movies`, `/movies/{movieId}` | any | Paged. |
| `POST` | **`/holds`** | CUSTOMER | → 201, or **409** on contention. |
| `POST` | `/bookings/{bookingId}/payment` | CUSTOMER | → 200 / 402 / 409 / 502. Idempotent. |
| `POST` | `/bookings/{bookingId}/cancel` | CUSTOMER | → 200 with refund breakdown. Idempotent. |
| `GET` | `/bookings` | CUSTOMER | Own history, paged, newest first. |
| `GET` | `/bookings/{bookingId}` | CUSTOMER | Own bookings only, else 403. |

**`POST /holds`**

```json
{ "showId": "…", "seatIds": ["…", "…"], "discountCode": "WELCOME50" }
```

Errors: `SHOW_NOT_FOUND` 404 · `SHOW_ALREADY_STARTED` 409 · `SEAT_NOT_IN_SHOW` 409 ·
`SEAT_ALREADY_HELD` 409 · `SEAT_ALREADY_BOOKED` 409 · `DUPLICATE_SEAT_IN_REQUEST` 400 ·
`TOO_MANY_SEATS` 400 · `DISCOUNT_*` 422 · `PRICING_RULE_NOT_FOUND` 404

**`POST /bookings/{id}/payment`**

```json
{ "paymentMethodToken": "pm_success", "idempotencyKey": "unique-per-attempt" }
```

The mock gateway recognises `pm_success` (approve), `pm_failure` (decline), `pm_error` (transport
failure). Any other value behaves like an ordinary card and follows `payment.mock.default-outcome`.

Errors: `HOLD_EXPIRED` 409 · `PAYMENT_FAILED` 402 · `PAYMENT_ALREADY_PROCESSED` 409 ·
`PAYMENT_GATEWAY_ERROR` 502 · `UNAUTHORIZED_BOOKING_ACCESS` 403

### Admin endpoints

All under `/api/v1/admin/**`, all requiring `ROLE_ADMIN` (enforced once, in `SecurityConfig`).

| Resource | Endpoints |
|---|---|
| Cities | `GET`/`POST` `/admin/cities` · `PUT`/`DELETE` `/admin/cities/{cityId}` |
| Movies | `GET`/`POST` `/admin/movies` · `PUT`/`DELETE` `/admin/movies/{movieId}` |
| Theaters | `POST` `/admin/theaters` · `GET`/`PUT`/`DELETE` `/admin/theaters/{theaterId}` |
| Screens | `GET`/`POST` `/admin/screens` (`?theaterId=`) · `GET`/`PUT`/`DELETE` `/admin/screens/{screenId}` |
| Seat layout | `GET`/`POST` `/admin/screens/{screenId}/seats` |
| Shows | `POST` `/admin/shows` · `PUT`/`DELETE` `/admin/shows/{showId}` |
| Pricing | `GET`/`POST` `/admin/pricing-rules` · `PUT`/`DELETE` `/admin/pricing-rules/{pricingRuleId}` |
| Discounts | `GET`/`POST` `/admin/discount-codes` · `GET`/`PUT`/`DELETE` `/admin/discount-codes/{discountCodeId}` |
| Refund policies | `GET`/`POST` `/admin/refund-policies` · `GET`/`PUT`/`DELETE` `/admin/refund-policies/{refundPolicyId}` |
| Users | `GET`/`POST` `/admin/users` |

`DELETE` **deactivates** rather than removes, wherever historical bookings reference the row.

**Seat layout** takes row descriptions rather than individual seats:

```json
{ "rows": [ { "rowLabel": "A", "seatCount": 10, "category": "PREMIUM" },
            { "rowLabel": "B", "seatCount": 12, "category": "REGULAR" } ] }
```

**Refund policy** — the ladder as data:

```json
{ "name": "Standard", "isDefault": true,
  "rules": [ { "minHoursBeforeShow": 0,  "maxHoursBeforeShow": 12,   "refundPercentage": 0 },
             { "minHoursBeforeShow": 12, "maxHoursBeforeShow": 24,   "refundPercentage": 50 },
             { "minHoursBeforeShow": 24, "maxHoursBeforeShow": null, "refundPercentage": 100 } ] }
```

> **Why no Swagger.** For an API this size, a generated page would largely restate this table while adding a
> dependency and annotation noise to every controller. This section plus the `curl` walkthrough in
> [§19](#19-running-the-application) is more useful and stays honest, because it is copied from a real run.

---

## 17. Authentication

**HTTP Basic over BCrypt** (strength 10). A minimal mechanism with real RBAC and no token lifecycle,
which keeps the sample commands short. OAuth or JWT is the upgrade path if this ever faces a browser
client.

The filter chain is **stateless** with CSRF disabled. There is no session and no cookie, so there is
nothing for a cross-site request to ride on; every request carries its own credentials. (Disabling CSRF on
a cookie-authenticated app would be a genuine vulnerability, which is why it is called out rather than
quietly switched off.)

**Where the rules live** — deliberately not duplicated:

| Rule | Enforced in |
|---|---|
| `/api/v1/admin/**` requires `ROLE_ADMIN` | `SecurityConfig` — a whole URL subtree, which is what URL matching is good at. |
| Individual customer operations require `ROLE_CUSTOMER` | `@PreAuthorize` on the handler, next to the code it protects. |
| *"This booking is yours"* | The service layer, against the loaded row — reported as `UNAUTHORIZED_BOOKING_ACCESS`. |

Ownership cannot be expressed as a URL rule because it depends on the row, which is the point.

401 and 403 are produced by `ExceptionTranslationFilter` — `GlobalExceptionHandler` deliberately re-throws
security exceptions rather than handling them, because that filter is the component that knows whether an
anonymous caller should get 401 or an authenticated one should get 403. Custom entry-point and
access-denied handlers render the same `ApiErrorResponse` shape, so a 401 from the filter chain looks
identical to a 409 from a service.

### Seeded development users

| Username | Password | Role |
|---|---|---|
| `admin` | `Admin@12345` | ADMIN |
| `ops.manager` | `Admin@12345` | ADMIN |
| `aisha` | `Customer@123` | CUSTOMER |
| `rahul` | `Customer@123` | CUSTOMER |
| `meera` | `Customer@123` | CUSTOMER |

Development credentials only. The BCrypt hashes in `V900__demo_data.sql` were generated with the
application's own encoder and verified to match.

---

## 18. Local setup

**Prerequisites:** Java 21 (Temurin), Maven 3.9+, Docker (for PostgreSQL and for the Testcontainers-based
tests).

### Option A — database in Docker, app on the host (recommended for development)

```bash
docker compose up -d postgres
./mvnw spring-boot:run
```

`docker-compose.yml` maps PostgreSQL to **port 5433** (not 5432) so it cannot collide with a local
installation. `application.properties` points at it by default.

### Option B — everything in Docker

```bash
docker compose up --build
```

Builds the application image and starts both containers; the app waits for the database's health check.

### Option C — your own PostgreSQL

```sql
CREATE DATABASE movie_ticket_booking;
```

then override the datasource:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/movie_ticket_booking \
SPRING_DATASOURCE_USERNAME=postgres \
SPRING_DATASOURCE_PASSWORD=postgres \
./mvnw spring-boot:run
```

**No credentials are hard-coded in Java.** Every value is a property with an environment-variable
override.

### Schema and seed data

Flyway runs on startup. `spring.flyway.locations` includes **both** `classpath:db/migration` (the schema)
and `classpath:db/seed` (demo data), so a fresh local instance is immediately explorable. The **test
profile deliberately excludes `db/seed`** — tests build explicit fixtures, so no test can be accidentally
satisfied by demo data it did not create. `SchemaMigrationIT.demoSeedDataIsNotLoadedInTests` asserts that.

Hibernate runs with `ddl-auto=validate`: Flyway owns the schema, and Hibernate only checks that the
entities match it. A mapping that drifts from a migration fails at startup.

Seed data provides 3 cities (one in a different time zone, so weekend pricing is visibly local), 4 movies,
4 theaters, 5 screens, 220 seats, 10 shows and 470 inventory rows, the full pricing cascade, two refund
policies, and five discount codes — two of which are deliberately unusable so the rejection paths can be
exercised without editing data. Show times are computed relative to `now()`, never hard-coded, so the seed
stays valid however long after it was written the project is checked out.

---

## 19. Running the application

```bash
docker compose up -d postgres
./mvnw spring-boot:run
curl -s localhost:8080/actuator/health          # {"status":"UP"}
```

### A complete walkthrough

Every block below is copied from a real run against the seeded database.

```bash
C='aisha:Customer@123'
B=http://localhost:8080/api/v1
```

**1. Authentication is required**

```bash
curl -s -o /dev/null -w '%{http_code}\n' "$B/cities"        # 401
```

**2. Browse**

```bash
curl -s -u "$C" "$B/cities"
curl -s -u "$C" "$B/cities/{cityId}/theaters"
curl -s -u "$C" "$B/theaters/{theaterId}/shows?to=2026-12-31T00:00:00Z"
```

**3. The seat map — note the pricing cascade**

```bash
curl -s -u "$C" "$B/shows/01930000-0006-7000-8000-000000000002/seats"
```

```
show     : The Silent Orbit @ Marine Drive Cineplex / Screen 1
startsAt : 2026-10-10T13:30:00Z ( Asia/Kolkata )   # 19:00 local, Saturday
dayType  : WEEKEND
PREMIUM  : [750.0]     <- screen-level rule (IMAX)
REGULAR  : [320.0]     <- city-level rule (Mumbai)
```

The same weekend show on a non-IMAX Mumbai screen resolves `PREMIUM: 540.0` from the city rule instead —
the scope cascade working, entirely from data.

**4. Hold seats, with a discount**

```bash
curl -s -u "$C" -X POST "$B/holds" -H 'Content-Type: application/json' -d '{
  "showId": "01930000-0006-7000-8000-000000000007",
  "seatIds": ["<seatId1>", "<seatId2>"],
  "discountCode": "WELCOME50"
}'
```

```
ref      : MTB-C9SG8AT8ZKQF
status   : HOLD_CREATED
seats    : ['A1', 'A2']
subtotal : 700.0 | discount WELCOME50 150.0 | total 550.0    # 50% capped at 150
expires  : 2026-09-28T11:28:13Z
```

**5. Another customer wants the same seats → 409**

```bash
curl -s -u 'rahul:Customer@123' -X POST "$B/holds" -H 'Content-Type: application/json' \
     -d '{"showId":"…","seatIds":["<seatId1>","<seatId2>"]}'
```

```json
{ "code": "SEAT_ALREADY_HELD",
  "message": "Seat(s) A2, A1 are currently held by another customer",
  "details": { "heldSeats": ["A2", "A1"] } }
```

**6. Pay**

```bash
curl -s -u "$C" -X POST "$B/bookings/{bookingId}/payment" -H 'Content-Type: application/json' \
     -d '{"paymentMethodToken":"pm_success","idempotencyKey":"demo-key-001"}'
```

```
status: CONFIRMED | confirmedAt 2026-09-28T11:23:13Z | paid 550.0
```

**7. Retry the same idempotency key — no second charge**

```bash
# identical request again
psql> SELECT count(*), string_agg(status, ',') FROM payments WHERE booking_id = '…';
      1 | SUCCESS
```

**8. Cancel — the refund policy applied**

```bash
curl -s -u "$C" -X POST "$B/bookings/{bookingId}/cancel"
```

```
booking : REFUNDED
refund  : 550.0 INR | 100.0 % | Standard | 29.89 h before show | COMPLETED
```

**Other paths worth trying:** `pm_failure` (402, seats released), `pm_error` (502, payment left
`PENDING`), `EXPIRED10` / `PAUSED25` (422), and booking show `…0009` versus `…0010` — both 6 hours out,
but one refunds 0% under the default policy and the other 100% under Andheri's own.

---

## 20. Running the tests

```bash
mvn clean verify
```

```
Tests run: 106  (surefire — unit,        no Docker needed)
Tests run: 109  (failsafe — integration, Testcontainers)
BUILD SUCCESS   ~45s
```

Unit tests are `*Test` and run under surefire; integration tests are `*IT` and run under failsafe, so the
fast feedback loop (`mvn test`) never needs Docker.

### Why not H2

Almost nothing this system relies on exists in H2's PostgreSQL compatibility mode, and the missing parts
are exactly the parts worth testing: `FOR UPDATE` blocking semantics, `SKIP LOCKED`, partial and
`NULLS NOT DISTINCT` unique indexes, `TIMESTAMPTZ` behaviour. A green suite on H2 would prove the code
runs, not that the concurrency guarantee holds.

One container is started per JVM and shared by every integration test; isolation comes from truncating
between tests rather than from a rollback, because the payment and concurrency flows depend on what one
transaction sees of another's **commits**.

### Coverage

| Area | Tests |
|---|---|
| **Unit** | `MoneyTest` (rounding vs. the DB check constraint) · `ShowSeatTest` (lazy expiry, token safety) · `BookingTest`, `BookingStatusTest` (state machine) · `PricingServiceTest` (scope cascade, local day type) · `DiscountCodeTest`, `DiscountServiceTest` (limits, validation order) · `RefundCalculatorTest`, `RefundServiceTest` (every band, boundaries, failing closed) · `MockPaymentGatewayTest` · `DayTypeTest` |
| **Schema** | `SchemaMigrationIT` — every constraint asserted with **raw SQL** that bypasses the application entirely |
| **Repository** | `ShowSeatLockingIT` — that `FOR UPDATE` really blocks, that unrelated rows do not, deterministic ordering |
| **Lifecycle** | `BookingLifecycleIT` — hold, pay, confirm, cancel, refund, and every failure path |
| **Expiry** | `HoldExpiryIT` — lazy expiry with the scheduler off, sweeper behaviour, the re-held-seat case |
| **Discounts** | `DiscountRedemptionIT` — validation, accounting, and a concurrent usage-limit race |
| **Notifications** | `NotificationIT` — transactional queueing, delivery thread, reminder idempotency |
| **HTTP** | `BookingApiIT` — auth, authz, validation, status codes, no token leakage |
| **Admin** | `AdminCatalogIT` — building a bookable venue from an empty catalogue |
| **Concurrency** | `SeatHoldConcurrencyIT` — [§21](#21-the-concurrency-test) |

---

## 21. The concurrency test

`SeatHoldConcurrencyIT`, three scenarios, real PostgreSQL.

### 1. Twenty customers, one seat

Twenty customers each try to hold the **same** seat. Every thread does its setup, reports ready, and then
blocks on a shared `CountDownLatch` — without that gate the first would finish before the last started and
no contention would ever occur. Java 21 **virtual threads** carry the load, which suits work that spends
its life blocked on a row lock.

Asserted:

- exactly **1** success and **19** failures;
- every failure is `SEAT_ALREADY_HELD` — not a lock timeout, not an optimistic-lock error, not a deadlock;
- `show_seats` shows the seat `HELD` under exactly **one** hold token;
- exactly **1** active booking exists for the show;
- exactly **1** allocation row (`booking_seats` joined to an active booking) exists for the seat.

The last three read straight from the database with raw SQL, bypassing the ORM.

### 2. Overlapping multi-seat requests

Twenty customers each want *two* of only *three* seats, deliberately requesting them in **different
orders**. This is where inconsistent lock ordering would show up as a deadlock and a partially applied
hold would show up as a seat allocated to a rejected booking. Asserted: no unexpected errors at all,
exactly one success, and held seats an exact multiple of the request size.

### 3. Independent seats must not contend

Every customer takes a *different* seat of the same show; all must succeed. This is the other half of the
guarantee — if the implementation locked the show or the seat table rather than individual inventory rows,
this test fails.

### Verifying the test can actually fail

A concurrency test that passes proves nothing unless it *can* fail. As a deliberate check,
`@Lock(PESSIMISTIC_WRITE)` was removed from `lockByShowAndSeatIds` and the suite re-run:

```
Tests run: 3, Failures: 2
[no attempt may fail with an unexpected error:
  ObjectOptimisticLockingFailureException: Batch update returned unexpected row count …
  for entity [ShowSeat with id '01a0e785-01e6-75fb-8824-3cee916a305a'] …]
```

Two things this establishes:

1. **The test genuinely detects the absence of the lock.** It is not passing by accident.
2. **The defence-in-depth layers are real.** Without the pessimistic lock, multiple transactions read the
   seat as available and collided on the `@Version` check — so no double-allocation occurred even then,
   but the failure mode degraded from a clean `409 SEAT_ALREADY_HELD` into a retry storm of optimistic
   lock exceptions. That difference is exactly what the pessimistic lock buys.

The lock was restored and the suite verified green again.

---

## 22. Trade-offs and known limitations

**Deliberate trade-offs**

| Decision | Cost accepted | Why |
|---|---|---|
| Pessimistic locking | Contending requests wait instead of failing fast. | Contention is the expected state here, and waiting a few ms beats a retry storm. Transactions are short and touch only DB rows. |
| Payment split across two transactions | A real window in which the hold can lapse mid-payment. | The alternative — holding row locks across a network call — is far worse. The window is handled explicitly with a compensating refund. |
| Cancellation commits before the gateway refund | A gateway rejection leaves a `FAILED` refund row needing operational follow-up. | A customer who asked to cancel should not stay booked because a third party had an outage. |
| Gateway transport failure leaves payment `PENDING` | Needs reconciliation; the hold lapses and the seats are released. | The outcome is genuinely unknown. Asserting "not charged" would be a guess that could cost a customer money. |
| Seat layouts are immutable once created | Changing a layout requires a data migration. | Existing shows have already materialised inventory from it; editing would orphan booked seats. |
| Single platform-wide currency | The Dubai seed city is priced in INR. | Multi-currency is a large feature, out of scope for now. |
| One pricing rule per (scope, category, day type), not time-versioned | A price change cannot be scheduled ahead. | Historical bookings are already protected by the price snapshot on `booking_seats`, so versioning would add overlap-resolution rules for no benefit. |
| Notification rejection logs and discards | A saturated queue delays delivery. | The row is already committed, so nothing recoverable is lost — and the alternatives either block the request thread or 500 a successful booking. |

**Known limitations**

- **No durable notification retry.** A `PENDING` row left by a rejected dispatch or a crash is not
  currently re-attempted. The schema and the dispatcher are shaped so a poller is purely additive —
  see [§14](#14-async-notification-workflow).
- **The reminder sweep is single-batch per tick.** Fine at this scale; a large backlog drains over several
  ticks.
- **Admins cannot bulk-cancel a show.** Cancelling a show with confirmed bookings is refused; each booking
  must be cancelled individually so each goes through the refund policy.
- **No rate limiting.** Out of scope, but a real deployment would want it on `/holds`.
- **Observability is plain Spring logging**, plus `/actuator/health` for the compose health check.
  Metrics and tracing are future work.
- **No read replicas or caching.** Seat maps hit the primary.

---

## 23. Future improvements

Roughly in order of value:

1. **Durable notification outbox** — one `@Scheduled` poller claiming `PENDING` rows with
   `FOR UPDATE SKIP LOCKED`, calling the existing `NotificationDispatcher.dispatch`. The schema is already
   shaped for it.
2. **Seat-map read caching**, with invalidation on hold and release. The legitimate use for Redis here —
   a caching concern, not a correctness one.
3. **Scheduled price changes** — add validity windows to `pricing_rules` with overlap resolution, now that
   the snapshot mechanism makes it safe.
4. **Multi-currency** — per-city currency plus money-with-currency as a value type.
5. **Partial cancellation** — release some seats from a booking rather than all.
6. **Bulk show cancellation** with automatic refunds, as an explicit admin operation with a confirmation
   step.
7. **Rate limiting** on hold creation.
8. **Seat-hold extension** — let a customer buy a few more minutes, once, mid-payment.
9. **Table partitioning** on `show_seats` and `bookings` by show date, once volume justifies it.
10. **Observability** — Micrometer timers on the hold transaction and lock wait times, which are the
    metrics that would actually matter here.

---

## Project layout

```
├── docker-compose.yml           postgres (:5433) + app
├── Dockerfile                   multi-stage build
├── pom.xml
├── docs/ai/                     AI-assisted development record
├── AGENTS.md / CLAUDE.md        working agreement for AI assistants on this repo
└── src
    ├── main
    │   ├── java/com/harshil/movieticketbooking/   163 files, ~10.7k lines
    │   └── resources
    │       ├── application.properties
    │       └── db
    │           ├── migration/   V1–V6, schema
    │           └── seed/        V900, demo data (excluded from tests)
    └── test
        ├── java/…/              29 files, ~5.1k lines
        └── resources/application-test.properties
```
