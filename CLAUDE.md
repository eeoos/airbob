# CLAUDE.md

Airbob is an accommodation booking platform. MySQL is authoritative for reservation inventory,
payment-operation state, and search projection input. Kafka records are wake-up signals, not domain
state. The README, `docs/` and `scripts/README.md` are written in Korean.

## Commands

- Run `./gradlew compileJava` after entity changes to regenerate QueryDSL Q-classes.
- `./gradlew test` needs Docker running (Testcontainers).
- Datasource and AWS credentials come from environment variables (`SPRING_DATASOURCE_*`, `AWS_*`,
  `CLOUDFRONT_DOMAIN`) supplied by `.env`.

## Package structure

Do not recreate root `kafka/`, root `outbox/`, generic translator, or catch-all DLQ packages.
Domain Kafka adapters live beside their domain (`domain/payment/messaging/`,
`domain/accommodation/cache/messaging/`, `search/messaging/`); reusable contracts and infrastructure
live under `messaging/`.

## Cross-cutting rules

- Redis is used for sessions, caches, recently viewed accommodations, and the settlement-generation
  lock (Redisson). It is never reservation authority: there is no external distributed reservation
  lock or best-effort Redis hold.
- Every accommodation-related mutation publishes `AccommodationSearchRefreshRequestedV1`.
- Integration events are appended through the transactional outbox (`OutboxWriter`) inside the
  business transaction. See `.claude/rules/messaging.md`.

## Area rules

Area-specific invariants live in `.claude/rules/`. Each file loads automatically when you read or
edit a file that matches its `paths`. Before planning a change in an area, read its rule file
directly, even if you have not opened a matching file yet.

| Rule file | Covers |
|---|---|
| `reservation.md` | Date inventory, quote and checkout, transitions, expiry cleanup, host list query |
| `payment.md` | PaymentOperation workflow, execution fence, manual resolution |
| `coupon.md` | Coupon issuance, time contract, stock preparation, lists and status |
| `messaging.md` | Outbox, Debezium, Kafka topics and listeners, DLT, operator alerts |
| `search.md` | Search snapshot rebuild, indexing, alias |
| `cache.md` | Accommodation detail cache and its dedicated Redis |
| `deploy-ci.md` | CI/CD, OCI deployment gates, archived AWS performance lab |
| `database.md` | Flyway migrations, roll-forward rules, migration notes |
| `api-docs.md` | OpenAPI contracts, API guides, snapshot regeneration, Swagger exposure |

## Testing expectations

- Use real MySQL tests for date-row lock ordering, NOWAIT classification, expired-hold takeover,
  cleanup/checkout races, transaction rollback, and migration constraints.
- Use Embedded Kafka for main → retry → DLT behavior and header sanitation.
- The `test` profile disables scheduled execution, Kafka listener auto-startup, inventory startup
  seeding and retention, and search index bootstrap. Listeners that declare their own `autoStartup`
  ignore the global switch, so each needs its own flag set to `false` in
  `src/test/resources/application-test.yaml`.
- Do not weaken `test`-profile Kafka/scheduling isolation to make a focused test pass.

## Working guidelines

- Keep diffs minimal: change only what the request needs, match the existing style, and leave
  adjacent code, comments, and formatting alone. Mention unrelated dead code instead of deleting
  it; remove only the imports, variables, and functions your own change orphaned.
- Write the simplest code that solves the problem: no unrequested features, configurability,
  single-use abstractions, or handling for cases that cannot happen.
- If a request is ambiguous or you are unsure, state your assumptions and ask before implementing.
- Verify before reporting done: reproduce a bug with a failing test before fixing it, and run the
  relevant tests before and after a refactor.
