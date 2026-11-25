# CLAUDE.md

Airbob is an accommodation booking platform. MySQL is authoritative for reservation inventory,
payment-operation state, and search projection input. Kafka records are wake-up signals, not domain
state. The README, `docs/` and `scripts/README.md` are written in Korean.

## Commands

- Run `./gradlew compileJava` after entity changes to regenerate QueryDSL Q-classes.
- `./gradlew test` needs Docker running (Testcontainers).
- Datasource and AWS credentials come from environment variables (`SPRING_DATASOURCE_*`, `AWS_*`,
  `CLOUDFRONT_DOMAIN`) supplied by `.env`.

## Module and package structure

The build is a multi-module Gradle project following team-dodn/spring-boot-java-template.
`core/core-api` is the only executable module and still holds all application code and resources,
including Flyway migrations. `support/monitoring` (`monitoring.yml`: actuator exposure, probes, metric
tags) and `support/logging` (`logging.yml`: log levels) are imported by core-api's
`spring.config.import`; readiness-group membership stays in core-api's `application*.yaml`.
`clients/*` modules hold only HTTP transport (no Spring beans of their own); core-api owns their
properties, bean wiring, and the mapping of provider responses to domain outcomes.
Versions live in `gradle/libs.versions.toml`. Build outputs stay at the
repository root (`build/libs/airbob.jar`, `build/openapi`) because deployment and CI read them there.
All application code is in the template layout: `core.api` (`controller.v1.<domain>`, `config`,
`security`), `core.domain.<domain>`, and `core.support`. The layering rules, the domain recipe
(Business inputs and outputs as domain records), and the PR-by-PR roadmap are in
`docs/architecture/code-structure.md`; follow it when adding or moving code.

ArchUnit tests in `core/core-api/src/test/java/kr/kro/airbob/architecture` enforce the layer rules
strictly for `kr.kro.airbob.core..` and freeze legacy violations (cross-domain repository use,
domain cycles, entity → DTO/service) in `core/core-api/src/test/resources/archunit_store`. Never add
entries to the store to make a new violation pass; fixing a violation prunes it, so commit the
shrunk store with the fix.

Do not recreate root `kafka/`, root `outbox/`, generic translator, or catch-all DLQ packages.
Domain Kafka adapters live beside their domain (`core/domain/payment/messaging/`,
`core/domain/accommodation/cache/messaging/`, `core/domain/search/messaging/`); reusable contracts and infrastructure
live under `core/support/messaging/`. Operator alerts are the `core/domain/operatoralert/` domain.

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
  `core/core-api/src/test/resources/application-test.yaml`.
- Do not weaken `test`-profile Kafka/scheduling isolation to make a focused test pass.

## AI code review

Before pushing a branch whose PR changes code (not only `docs/` or `*.md`; agent policy in `CLAUDE.md`,
`AGENTS.md`, `.claude/`, and the review criteria `docs/development/code-review.md` count as code),
follow the `pr-ai-review` skill: a Codex review of the branch diff, then verify, fix, and record each
finding. After the PR's Claude review comment arrives, follow part B of the same skill.

## Behavioral guidelines

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
