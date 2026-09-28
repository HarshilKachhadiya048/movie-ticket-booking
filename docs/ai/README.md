# AI-assisted development record

The assignment asks for documentation of how AI assistance was used. This directory is that record, kept
in the repository so the process is auditable rather than asserted.

## What is here

| File | Contents |
|---|---|
| [`master-prompt.md`](master-prompt.md) | The specification I wrote before any code existed: stack, architecture, concurrency approach, data model, transaction rules, testing requirements, code conventions. |
| [`implementation-plan.md`](implementation-plan.md) | The thirteen-phase build order, and what actually happened in each phase. |
| [`decisions.md`](decisions.md) | Decision record. Which calls were mine up front, which were open questions I resolved during the build, and the reasoning for each. |

## An honest account of the split

**What I decided, before any code was written**, and specified in `master-prompt.md`:

- the stack (Java 21, Spring Boot 4.1.1, PostgreSQL, Flyway, JPA, Testcontainers) and the explicit
  exclusion of Redis, Kafka, SQS and MongoDB;
- the modular-monolith structure and the domain package breakdown;
- **the concurrency strategy** — PostgreSQL row-level locking via `@Lock(PESSIMISTIC_WRITE)` or
  `SELECT … FOR UPDATE`, with in-memory and distributed locks explicitly ruled out, deterministic lock
  ordering, and lazy expiry evaluated under the lock so correctness never depends on a scheduler;
- **the transaction boundaries** — that no database transaction may be open across a payment call, and
  the specific three-step hold / charge / confirm shape;
- the core data model down to the `show_seats` columns, the booking state machine, and the requirement
  that pricing and refund policies be configuration rather than code;
- the async notification mechanism (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async` on a bounded
  executor) and reminder idempotency;
- the testing requirements, including the shape of the concurrency test and the insistence on real
  PostgreSQL over H2;
- the code conventions: centralised endpoint constants, explicit `@Column` definitions, DTOs at
  boundaries, no business logic in controllers, enums over strings, `BigDecimal` for money, and no
  gratuitous design patterns.

**What I was asked to decide during the build**, because the specification left them genuinely open:

1. Primary key strategy. I chose **UUIDv7** over UUIDv4 and over a bigint/public-UUID hybrid, for index
   locality on `show_seats` without giving up non-enumerable ids.
2. Transactional outbox. I chose **not** to build one, but required the design be extendable to one
   without a rewrite.
3. Mock gateway determinism. I chose **per-request magic tokens** (`pm_success` / `pm_failure` /
   `pm_error`) over global configuration, so a single test run can drive every path.
4. Container runtime for Testcontainers — I started Docker Desktop myself.

**What the assistant did:** wrote the code, migrations and tests against that specification; flagged the
four ambiguities above rather than guessing; and surfaced the implementation-level problems recorded in
`decisions.md` — including the rollback bug in `prepare()`, the missing `spring-boot-flyway` artifact, and
the `@ConditionalOnMissingBean` misuse. Where those needed a design answer rather than a fix, the answer
is mine and is documented.

**Verification, not assertion.** Claims in the README were checked rather than assumed:

- `mvn clean verify` — 106 unit and 109 integration tests, against real PostgreSQL;
- the application started against a real database, all seven migrations applied, and the documented
  `curl` walkthrough was executed end to end — the outputs quoted in README §19 are from that run;
- the concurrency test was deliberately broken (the pessimistic lock removed) to confirm it actually
  fails without the lock, then restored. Recorded in README §21.

## Tooling

| Tool | Used for |
|---|---|
| Claude Code (Opus) | Implementation against the specification in `master-prompt.md`. |
| Docker + Testcontainers | Real PostgreSQL 16 for every integration test; `docker compose` for local runs. |
| Maven (surefire / failsafe) | Unit and integration test separation. |
| `javap` / `unzip` against the dependency jars | Verifying Boot 4 / Hibernate 7 / Jackson 3 / Testcontainers 2 APIs rather than assuming the previous majors' signatures. |
