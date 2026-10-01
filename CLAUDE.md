# CLAUDE.md

Guidance for Claude Code (and other AI assistants) working in this repository.

## Read these first

1. **[AGENTS.md](AGENTS.md)** — the working agreement. Ten non-negotiable rules, layout and style,
   testing conventions. Everything in it applies here; it is not repeated in this file.
2. **[README.md](README.md)** — what the system does and why it is built this way. Sections 6, 11, 12 and
   14 cover the concurrency, expiry, payment and notification designs, which is where most of the
   non-obvious decisions live.
3. **[docs/ai/decisions.md](docs/ai/decisions.md)** — the decision record. Check it before proposing an
   architectural change; several obvious-looking alternatives were considered and rejected for stated
   reasons.

## The short version

This is a modular monolith. The hard requirement is that **two customers can never both be told they have
the same seat**, and that guarantee rests on PostgreSQL row locks — not on application coordination.
Before changing anything in `booking`, `show` or `payment`, read README §6.

Four rules cause the most trouble if missed:

| Rule | Consequence of breaking it |
|---|---|
| Seat allocation uses `SELECT … FOR UPDATE` | Double allocation, or a degraded retry storm |
| No transaction open across a gateway call | Row locks held for the length of somebody else's outage |
| Failures inside a transaction are **returned**, not thrown | The rollback undoes the record of the failure |
| Time comes from the injected `Clock` | Tests must sleep; validation disagrees with services |

## Working style expected here

**Verify rather than assume.** This project runs on Spring Boot 4 / Hibernate 7 / Jackson 3 / Testcontainers 2,
where several APIs moved from where they were in the previous majors. Three examples that cost real time:

- Flyway autoconfiguration lives in `org.springframework.boot:spring-boot-flyway`, a separate artifact in
  Boot 4. Without it Flyway is on the classpath but never runs, and the app starts against an empty schema.
- `AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure`.
- `HandlerMethodValidationException.getAllValidationResults()` is now `getParameterValidationResults()`.

Check the jar (`javap`, `unzip -l`) rather than reaching for what the API used to be.

**Run the tests.** Every significant bug found during the initial build was found by a test, not by
reading the code

**Prefer the database.** Where a guarantee can be expressed as a constraint, it is: unique indexes,
partial unique indexes, check constraints. Application checks exist to produce a good error message; the
constraint is what makes the guarantee true.

**Don't add abstractions speculatively.** There is no factory, no visitor, no abstract base service. The
patterns that are here — `PaymentGateway`, `DiscountType`'s per-constant arithmetic, `PricingScope` —
each earn their place. Adding one "for extensibility" is a change that needs justifying in the PR.

## Scope boundaries

Deliberately out of scope for now: frontend, deployment, CI/CD, microservices, OAuth/JWT/SSO,
production observability. `Dockerfile` and `docker-compose.yml` exist only so the project runs locally in
one command.
