# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Airbob is an accommodation booking platform built on Spring Boot 3.5.8 and JDK 21. MySQL is
authoritative for reservation inventory, payment-operation state, and search projection input.
Kafka records are wake-up signals, not domain state. The README, `docs/` and `scripts/README.md`
are written in Korean.

## Commands

```bash
# Compile, including QueryDSL Q-class generation after entity changes
./gradlew compileJava

# Run all tests (Docker must be running)
./gradlew test

# Run one test class or method
./gradlew test --tests "kr.kro.airbob.domain.reservation.ReservationConcurrencyTest"
./gradlew test --tests "kr.kro.airbob.domain.reservation.ReservationConcurrencyTest.reservationConcurrencyTest"

# Regenerate and verify OpenAPI snapshots after API changes
./gradlew updateOpenApi verifyOpenApi

# Build or run
./gradlew build -x test
./gradlew bootRun
```

- `docker compose up -d` starts the local stack from `docker-compose.yml`: MySQL 8.4, Kafka with
  topic init, Debezium with connector registration, Elasticsearch/Logstash/Kibana, two Redis
  instances (general and accommodation cache), and Prometheus/Grafana/Kafka UI.
- `application.yaml` activates the `dev` profile by default. Datasource and AWS credentials come
  from environment variables (`SPRING_DATASOURCE_*`, `AWS_*`, `CLOUDFRONT_DOMAIN`) supplied by `.env`.
  Local P6Spy read tracing runs with `dev` only, excluding `aws`, `oci` and `test`.
- Integration tests use Testcontainers. There is no shared container base class: each test declares
  its own (mostly `mysql:8.0.33`, some `mysql:8.4.x`, `redis:7.2-alpine`, and an Elasticsearch image
  built from `docker/elasticsearch/`). Gradle runs tests with `user.timezone=UTC`.
- `./gradlew test` excludes `**/apidocs/**`; those tests run only through `generateOpenApi`. `check`
  depends on `verifyOpenApi`, so `./gradlew build` (even with `-x test`) fails when a controller or
  DTO change leaves the OpenAPI snapshots stale.

## Package structure

```text
kr.kro.airbob/
├── common/          Shared response, exception, audit history, request context, validation, monitoring
├── config/          Web, JPA, QueryDSL, Redis, Elasticsearch, scheduling, retry, OpenAPI (apidocs/)
├── cursor/          Cursor pagination annotation and argument resolvers
├── geo/             Google Maps geocoding
├── domain/          Business modules
│   ├── reservation/     DB-authoritative booking, date inventory, and expiration cleanup
│   ├── payment/         PaymentOperation orchestration, Toss adapter, recovery, admin resolution
│   ├── accommodation/   Listing domain and isolated detail-cache components
│   ├── coupon/          Coupon campaigns and SKIP LOCKED issuance
│   └── auth, member, review, wishlist, recentlyviewed, settlement, statistics, image, commoncode
├── messaging/
│   ├── event/           Canonical integration event descriptor, envelope, and codec
│   ├── outbox/          Transactional outbox, retention cleanup, and health monitoring
│   ├── alert/           Durable operator-alert stream
│   └── infrastructure/  Shared Kafka retry and header sanitization infrastructure
└── search/          MySQL-snapshot-driven Elasticsearch search and indexing
```

Do not recreate root `kafka/`, root `outbox/`, generic translator, or catch-all DLQ packages.
Domain Kafka adapters live beside their domain (`domain/payment/messaging/`,
`domain/accommodation/cache/messaging/`, `search/messaging/`); reusable contracts and infrastructure
live under `messaging/`.

## Cross-cutting rules

- Redis is used for sessions, caches, recently viewed accommodations, and the settlement-generation
  lock (Redisson). It is never reservation authority: there is no external distributed reservation
  lock or best-effort Redis hold.
- Every accommodation-related mutation publishes `AccommodationSearchRefreshRequestedV1`.
  Accommodation, review, reservation, and payment code paths do this today.
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
| `database.md` | Flyway migrations, roll-forward rules, migration map |

## Testing expectations

- Use real MySQL tests for date-row lock ordering, NOWAIT classification, expired-hold takeover,
  cleanup/checkout races, transaction rollback, and migration constraints.
- Use Embedded Kafka for main → retry → DLT behavior and header sanitation.
- Testcontainers cover MySQL, Redis, and Elasticsearch where the behavior depends on the real
  implementation.
- The `test` profile disables scheduled execution, Kafka listener auto-startup, inventory startup
  seeding and retention, and search index bootstrap. Listeners that declare their own `autoStartup`
  ignore the global switch, so each needs its own flag set to `false` in
  `src/test/resources/application-test.yaml`.
- Do not weaken `test`-profile Kafka/scheduling isolation to make a focused test pass.

## API documentation

- Controller/DTO annotations and `config/apidocs/` are the source of the V1 OpenAPI contracts.
  Keep summaries, success statuses, headers, examples, `@ApiErrors`, and unique nested DTO schema
  names aligned with actual behavior. Do not infer authentication from `@CurrentMemberId` alone.
- Update `docs/api/` guides when authentication, pagination, time semantics, or workflows change.
  Use synthetic examples and keep API-facing payment states distinct from internal operation states.
- Run `./gradlew updateOpenApi verifyOpenApi` after API changes and include generated YAML snapshots
  in the same change. Never hand-edit `docs/api/openapi/*.yaml`; ordinary builds only verify them.
- `generateOpenApi` uses isolated MVC tests with real controllers, Jackson, resolvers and auth guards,
  and mocked service/infrastructure dependencies. Preserve normal `test` profile isolation.
- Swagger UI and live OpenAPI endpoints are enabled for `dev`, `aws`, and `oci` on the API origin.
  Keep them disabled when `test` is active unless documentation tests explicitly enable them.
  The shared documentation URL is `https://api.airbob.cloud/swagger-ui/index.html`, with `client`
  and `admin` (`/api/v1/admin/**`) groups.
- API-documentation-only CI must validate contracts without requesting OCI deployment. Required
  `CI result` includes documentation job outcomes; app builds already validate contracts via `check`.

## External integrations

- Toss Payments: `domain/payment/service/TossPaymentsAdapter.java`, behind the provider-neutral
  `domain/payment/service/gateway/PaymentProviderGateway`
- Google Maps geocoding: `geo/`
- AWS S3 and CloudFront: image delivery (`domain/image/`)
- Elasticsearch: `search/`
- Slack: `messaging/alert/infrastructure/slack/`

## Authentication

- Redis-backed sessions are enforced by `SessionAuthFilter`.
- `UserContext` carries request-scoped member information.
- `/api/v1/admin/**` is protected by `AdminAuthInterceptor`, which re-checks active ADMIN role from
  MySQL.

## Documentation layout

`docs/api/` holds per-feature API guides and the generated `openapi/` snapshots. `docs/operations/`
holds the runbooks referenced by this file and `.claude/rules/`. `docs/performance/` holds the
measurement write-ups linked from the README.

## Working guidelines 

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

### 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

### 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.
