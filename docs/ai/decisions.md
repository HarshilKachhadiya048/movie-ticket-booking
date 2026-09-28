# Decision record

Every decision that shaped this codebase, with the reasoning and the alternatives rejected.

Each entry is tagged with who decided it and when:

- **[SPEC]** — decided by me up front and written into [`master-prompt.md`](master-prompt.md).
- **[ASKED]** — a genuine ambiguity raised back to me during the build, and the answer I gave.
- **[BUILD]** — an implementation-level decision made while writing the code, within the constraints the
  specification already set.

---

## ADR-001 — PostgreSQL row-level locking for seat allocation **[SPEC]**

**Decision.** Seat allocation is serialised by `SELECT … FOR UPDATE` on `show_seats`, via
`@Lock(LockModeType.PESSIMISTIC_WRITE)`. Validation and mutation both happen inside that lock's
transaction.

**Why.** The guarantee has to hold across threads, connections, application instances and restarts. Only
the database can provide that, because the database is where the data is.

**Rejected:**

| Alternative | Why not |
|---|---|
| `synchronized` / `ReentrantLock` | Coordinates one JVM. Two instances and the guarantee is gone. |
| Redis / distributed lock | A second source of truth that can disagree with the database — see ADR-004. |
| Optimistic locking alone | Works, but every loser does the full pricing and discount work and only fails at commit, reporting "please retry" rather than "that seat is taken". Under a burst it becomes a retry storm. Kept as a *secondary* defence via `@Version`. |
| `SERIALIZABLE` isolation | Pushes cost onto every transaction in the system and produces serialization failures that all need retry logic — to solve a problem confined to one table. |

**Consequence.** Contending requests wait a few milliseconds instead of failing fast. Acceptable, because
the transaction is short and touches only database rows — no network call happens inside it (ADR-005).

---

## ADR-002 — Lazy hold expiry, evaluated under the lock **[SPEC]**

**Decision.** `ShowSeat.effectiveStatus(now)` reports a lapsed `HELD` row as `AVAILABLE`. The scheduled
sweeper only tidies up.

**Why.** The requirement is that a scheduler must not be the only thing making an expired seat bookable.
Evaluating expiry under the row lock means correctness never depends on the sweeper at all. It can run
late, be switched off, or never have run in this deployment.

**Consequence.** The stored row can legitimately be stale. Every availability query — the seat map, the
show listing counts — must therefore apply the same rule, or customers would be told a seat is taken when
they can in fact book it. `ShowSeatRepository.countAvailable` does.

**Related [BUILD] decision — release is guarded by the hold token, never by status.** Once a hold lapses,
another customer can take the seat through lazy expiry, and their hold carries a different token. A
sweeper releasing on status alone would take a seat away from someone holding it legitimately.
`ShowSeat.isHeldUnder(token)` exists for exactly this, and `HoldExpiryIT` covers the sequence.

---

## ADR-003 — Payment spans two transactions with the gateway between them **[SPEC]**

**Decision.** Transaction 1 records a `PENDING` payment and commits. The gateway is called with no
transaction open. Transaction 2 re-locks the booking and its seats, re-verifies the hold, and applies the
outcome.

**Why.** A gateway call can take seconds and can hang. Holding row locks across it would block every other
customer booking adjacent seats and tie up a pooled connection for the duration of a third party's outage.

**[BUILD] consequence 1 — orchestration must live in a separate bean.** `@Transactional` works through a
proxy, so a method calling another method on `this` bypasses it. One class would have silently produced a
single long transaction spanning the gateway call. `BookingPaymentService` therefore carries no
`@Transactional` at all and delegates to `BookingPaymentTransactionService`.

**[BUILD] consequence 2 — failures are returned, not thrown.** The transaction that records "declined,
seats released, booking terminal" must commit. This was not theoretical: `prepare()` originally expired
the booking, released the seats and then threw, rolling back both.

**[BUILD] consequence 3 — the hold can lapse mid-payment.** The specification does not cover it. Handled
explicitly: the payment is recorded `SUCCESS` because money genuinely moved, the booking expires, and a
compensating refund is issued outside the transaction. Pretending otherwise would mean charging a customer
and giving them nothing.

---

## ADR-004 — No Redis **[SPEC]**

**Decision.** Seat holds live in PostgreSQL. No Redis anywhere.

**Why.** Redis is the reflex answer for seat holds and it is the wrong tool. The seat's real state must be
joined to bookings, payments and refunds, all of which live in PostgreSQL — so a hold in Redis is a second
source of truth that can disagree after an eviction, a failover, or a partial failure between the two
writes. Reconciling them is a distributed-transaction problem invented entirely by the choice of Redis.
A TTL would also expire the hold while leaving the booking row stale, so the reconciliation is needed
anyway.

**Where it would be justified:** caching seat-map *reads*, which is a performance concern, not a
correctness one.

---

## ADR-005 — No message broker **[SPEC]**

**Decision.** Notifications use `@TransactionalEventListener(AFTER_COMMIT)` plus `@Async` on a bounded
executor. No Kafka, SQS or RabbitMQ.

**Why.** A broker does not solve the hard part. The difficulty is atomicity — "the booking is confirmed"
and "a confirmation is owed" must commit together. Publishing to a broker from inside a transaction
reintroduces the dual-write problem, which is the classic reason people then add an outbox. A row in the
same database gives that atomicity for free.

---

## ADR-006 — UUIDv7 primary keys **[ASKED]**

**Options put to me:** UUIDv7 everywhere; BIGINT identity plus a public UUID; plain UUIDv4.

**Decision: UUIDv7 everywhere**, via `@UuidGenerator(algorithm = UuidVersion7Strategy.class)`.

**Why.** UUIDs are required, and they keep ids non-enumerable and generatable without a database round
trip. UUIDv4 pays for that with random insert positions — every row lands on an arbitrary B-tree page,
fragmenting the index and bloating the WAL. That matters most on `show_seats`, the one table under real
concurrent write pressure. UUIDv7 embeds a millisecond timestamp in its high bits, so inserts stay at the
right-hand edge of the index the way a sequence would, while remaining genuine UUIDs.

The BIGINT-plus-public-UUID hybrid has the tightest indexes but doubles the identity of every row, and
every repository and foreign key then has two identities to reason about. Not worth it at this scale.

**Consequence.** Seed data generates v7-shaped ids too, via a small SQL helper dropped at the end of the
migration, so seed rows are not obvious outliers. Note that `@UuidGenerator(style = TIME)` is *not* v7 —
the explicit `algorithm` is required.

---

## ADR-007 — No transactional outbox, but the design stays extendable **[ASKED]**

**Decision.** Do not build the outbox now. Shape the design so one can be added without a rewrite.

**Why.** The brief makes it optional, and the durability gap it closes is narrow: a crash between commit
and dispatch. At this scale that does not justify the extra table and poller. But the decision should not
be expensive to reverse.

**How extendability was preserved [BUILD]:**

- the notification row is written **transactionally**, which is the hard half of the outbox pattern and is
  already done;
- `notifications` already carries `status`, `attempt_count` and `available_at`, plus the partial index
  `ix_notifications_pending ON (available_at) WHERE status = 'PENDING'`;
- `NotificationDispatcher.dispatch(UUID)` is the single delivery entry point, taking an id and reading the
  row itself;
- `BookingRepository.claimBookingsDueForReminder` is a working `FOR UPDATE … SKIP LOCKED` claim query to
  copy.

Adding durability is **one new class** — a `@Scheduled` poller claiming `PENDING` rows and calling the
existing `dispatch`. No migration, no rewrite.

---

## ADR-008 — Mock gateway outcomes are chosen per request **[ASKED]**

**Options put to me:** magic payment-method tokens; a global configuration property; encoding the outcome
in the charge amount.

**Decision: magic tokens** — `pm_success`, `pm_failure`, `pm_error`.

**Why.** The outcome travels with the request, so one integration test can drive a successful payment and
the next a decline, in the same application context, with no property juggling, no mocking of the gateway
bean and no shared mutable state between tests. It also mirrors how real providers ship test tokens.

A global property would have made the outcome shared state — a single test could not exercise both paths.
Encoding it in the amount is obscure and collides with real pricing arithmetic.

**Consequence [BUILD].** `pm_error` *throws* rather than returning a decline, which forced the
distinction that matters most: a decline is a definite "no" and the seats can be released with confidence;
a transport failure means the outcome is **unknown**, so the payment stays `PENDING` for reconciliation.

---

## ADR-009 — Pricing resolved by scope specificity, entirely from data **[SPEC + BUILD]**

**[SPEC]** Pricing must not be hard-coded; prices are keyed on seat category and day type, and the charged
price is snapshotted onto `booking_seats`.

**[BUILD] Decision.** A rule is scoped at exactly one of screen / theater / city / global. The most
specific match wins. All candidates come back in a single query and are reduced in memory.

**Why.** Gives a platform-wide default that any level can override, entirely as data — a chain sets a
baseline, a city charges more, one IMAX screen charges more again. No `if (category == PREMIUM)` anywhere.
One query regardless of seat count.

**[BUILD] Decision — not time-versioned.** Deliberately. Historical bookings are already protected by the
price snapshot, so validity windows would add overlap-resolution rules for no benefit the snapshot does
not already provide.

**[BUILD] Decision — fails closed.** A seat with no matching rule raises `PRICING_RULE_NOT_FOUND` rather
than defaulting to zero. Handing out free tickets because configuration is incomplete is worse than
refusing the booking.

---

## ADR-010 — Weekend is a *local* question **[BUILD]**

**Decision.** Cities carry an IANA time zone. Day type is derived from the show's instant projected into
the city's zone, never UTC and never the server's default.

**Why.** A 00:30 Saturday show in Mumbai is 19:00 Friday in UTC. Pricing it off the UTC date would
silently charge weekday rates for a weekend screening. This is a correctness bug that would be invisible
in a single-timezone deployment and would appear the moment the platform expanded.

`DayType.of` takes an already-localised `LocalDate` rather than an `Instant`, so the caller is forced to
say whose calendar it is asking about. The seed data includes a city in a different zone so the behaviour
is observable, and `PricingServiceTest` pins the Friday-in-UTC / Saturday-locally case.

---

## ADR-011 — Discounts reserved at hold time, released on failure **[SPEC]**

**Decision.** The code is validated and its redemption **reserved** when the hold is created, under a
pessimistic lock on the code row. The reservation is released if the booking does not complete — expiry,
payment failure or cancellation.

**Why.** The customer sees the final payable amount before paying, and cannot have their last remaining
use taken by somebody else mid-payment. Releasing on failure means an abandoned hold does not permanently
consume a promotional code nobody actually used.

`used_count` is maintained as an exact mirror of the number of live `discount_code_usages` rows — deleting
the row and decrementing together keeps that invariant true, and `DiscountRedemptionIT` asserts it.

**Lock ordering [BUILD]:** the code is always locked **after** the seats, never before. A fixed global
order across all lockable resources is what prevents deadlock between two bookings using the same code.

---

## ADR-012 — Refund bands are half-open intervals **[SPEC]**

**Decision.** `[min, max)` hours, with a null max meaning unbounded. Overlapping bands are rejected at
creation with 409.

**Why.** Adjacent bands share an endpoint without overlapping or leaving a gap, so a cancellation at
exactly 24 hours has precisely one answer. With overlapping bands the refund would depend on which row the
database happened to return first — the same cancellation could yield 50% or 100%. Better to reject the
configuration than to discover it when a customer complains.

Hours are computed from seconds rather than rounded to whole hours, so 23h59m stays in the 50% band
instead of being rounded up and over-refunded.

**Fails closed:** no policy, or a gap in the ladder, yields 0%. Misconfiguration should under-refund
visibly, not give money away silently.

---

## ADR-013 — Cancellation commits before the gateway refund **[SPEC]**

**Decision.** The booking is cancelled, the seats released and the refund row written in transaction 1.
The gateway is called afterwards. A rejected refund is left as a `FAILED` row.

**Why.** The customer's cancellation should succeed, and the seats should go back on sale, even if the
payment provider is slow or down. Unwinding the cancellation would tell a customer who asked to cancel
that they are still booked because a third party had an outage. A `FAILED` refund row is an operational
problem with an owner; a silently reverted cancellation is a customer-facing one.

---

## ADR-014 — `EXPIRED` added; `PAYMENT_FAILED` terminal; `REFUNDED` distinct from `CANCELLED` **[BUILD]**

The specification listed six states and invited refinement.

- **`EXPIRED` added.** "The hold lapsed" and "the customer changed their mind" are different facts, and
  conflating them into `CANCELLED` loses information that matters for reporting.
- **`PAYMENT_FAILED` is terminal.** A decline releases the seats, so there is nothing left to retry
  against — they may already belong to somebody else. Allowing a transition back to `PAYMENT_PENDING`
  would be dishonest about what actually has to happen, which is a fresh hold.
- **`REFUNDED` distinct from `CANCELLED`.** Both mean cancelled; `REFUNDED` additionally means money went
  back. Since a cancellation inside the 0% band is legitimate and returns nothing, the two outcomes are
  worth distinguishing.

Enforced by `Booking.transitionTo`, which rejects any move not in the graph. Re-applying the current state
is a no-op, which is what makes the payment and cancellation paths safely retryable.

---

## ADR-015 — Business-rule violations are 422, conflicts are 409 **[BUILD]**

**Decision.** 409 for conflicts over shared mutable state — seat availability, concurrency, booking
lifecycle, duplicates. 422 for a well-formed request that violates a configured business rule — discount
validity and limits, refund policy bands, seat layout already present.

**Why.** The specification mandates 409 for seat and domain conflicts. Reusing it for "your discount code
expired" would blur two genuinely different situations: one is *somebody else got there first, try
different seats*, the other is *this input is not usable, nothing about the system will change that*.

Ownership failures report **403, not 404**. Both are defensible; the caller already authenticated and
would have to guess a UUIDv7, so hiding existence buys nothing the id space does not already provide.

---

## ADR-016 — Money is normalised *before* arithmetic **[BUILD]**

**Decision.** `Money` rounds to scale 2 with HALF_UP before any further computation.

**Why.** Not cosmetic. `bookings` carries `CHECK (total_amount = subtotal_amount - discount_amount)`, and
PostgreSQL rounds on store. A discount of `10.005` stores as `10.01`, but a total computed from the
unrounded value gives `89.995 → 90.00`, and `100.00 - 10.01 ≠ 90.00` violates the constraint. Normalising
first keeps the arithmetic Java did identical to the arithmetic the database sees.
`MoneyTest.subtractMatchesStoredValues` pins the exact case.

---

## ADR-017 — Time comes only from an injected `Clock` **[BUILD]**

**Decision.** No `Instant.now()` in production code. Hold expiry, discount windows, refund bands and
weekend pricing all read the `Clock` bean — including bean validation, via `ValidationConfig`.

**Why.** Testing a five-minute hold expiry by sleeping for five minutes produces a suite nobody runs. With
an injectable clock, `HoldExpiryIT` advances time and asserts the behaviour in microseconds.

The validation half is a genuine correctness point, not just convenience: `@Future` is evaluated against a
`ClockProvider` that defaults to the system clock, so without `ValidationConfig` the validator and the
service layer would disagree about what time it is. This surfaced as an admin endpoint rejecting a show as
"in the past" that the service considered comfortably in the future.

---

## ADR-018 — `@ConditionalOnMissingBean` only inside auto-configuration **[BUILD]**

**Decision.** Removed from `LoggingNotificationSender` and `ClockConfig`. Tests override beans with
`@Primary` instead.

**Why.** Outside auto-configuration the condition is evaluated against whatever beans happen to be
registered so far, which depends on scan order. Here it silently skipped registering the notification
sender, and the application context failed to start with an unsatisfied dependency. The failure mode is
the wrong way round — it does not fall back, it disappears.

---

## ADR-019 — Integration tests run on real PostgreSQL, never H2 **[SPEC]**

**Decision.** Every `*IT` runs against PostgreSQL 16 via Testcontainers. H2 is not a dependency.

**Why.** Everything this system leans on is PostgreSQL-specific: `FOR UPDATE` blocking semantics,
`SKIP LOCKED`, partial unique indexes, `UNIQUE NULLS NOT DISTINCT`, `TIMESTAMPTZ`. A green H2 suite would
prove the code runs, not that the concurrency guarantee holds — which is the only thing worth proving here.

**[BUILD] consequences.** Isolation comes from truncating between tests, not from a rollback: the
behaviour under test is what one transaction sees of another's *commits*, which a test-managed transaction
would hide. One container is shared per JVM. Database-level guarantees are asserted in raw SQL that
bypasses the ORM entirely (`SchemaMigrationIT`), because "the database guarantees X" is only worth
something if X holds against a statement that did not go through application validation.

---

## ADR-020 — The concurrency test was verified by breaking it **[BUILD]**

**Decision.** `@Lock(PESSIMISTIC_WRITE)` was deliberately removed and the suite re-run, to confirm
`SeatHoldConcurrencyIT` actually fails without it. Then restored and re-verified.

**Why.** A concurrency test that passes proves nothing unless it *can* fail. Latch-gated races are
particularly easy to get subtly wrong — if the threads do not genuinely overlap, the test passes while
testing nothing.

**What it showed.** The test went red, and informatively: the `@Version` optimistic lock caught the race
instead, so no double-allocation occurred even without the pessimistic lock — but the failure mode
degraded from a clean `409 SEAT_ALREADY_HELD` into a storm of optimistic lock exceptions. That difference
is precisely what the pessimistic lock buys, and it is now documented rather than assumed.

This is recorded in README §21 so a future reader can repeat it.
