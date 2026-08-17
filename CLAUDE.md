# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build and test commands

```bash
# Compile, including QueryDSL Q-class generation after entity changes
./gradlew compileJava

# Run all tests
./gradlew test

# Run one test class or method
./gradlew test --tests "kr.kro.airbob.domain.reservation.ReservationConcurrencyTest"
./gradlew test --tests "kr.kro.airbob.domain.reservation.ReservationConcurrencyTest.reservationConcurrencyTest"

# Build or run
./gradlew build -x test
./gradlew bootRun
./gradlew packageZip
```

The application requires external infrastructure. Integration tests use Testcontainers where
appropriate. The `test` profile disables scheduled execution and Kafka listener auto-startup.

## Current architecture

Airbob uses Spring Boot 3.5.8 and JDK 21. MySQL is authoritative for reservation inventory,
payment-operation state, and search projection input. Kafka records are wake-up signals, not domain
state.

### Package structure

```text
kr.kro.airbob/
├── common/                  Shared response, exception, audit, and monitoring code
├── config/                  Web, JPA, Redis, Elasticsearch, and scheduling configuration
├── cursor/                  Cursor pagination annotation and argument resolvers
├── domain/                  Business modules
│   ├── reservation/         DB-authoritative booking and expiration cleanup
│   ├── payment/             PaymentOperation orchestration, gateway, recovery, admin resolution
│   └── accommodation/...    Accommodation and supporting domains
├── messaging/
│   ├── event/               Canonical integration event descriptor, envelope, and codec
│   ├── outbox/              Transactional outbox, retention cleanup, and health monitoring
│   ├── alert/               Durable operator-alert stream
│   └── infrastructure/      Shared Kafka retry and header sanitization infrastructure
└── search/                  MySQL-snapshot-driven Elasticsearch search and indexing
```

Do not recreate root `kafka/`, root `outbox/`, generic translator, or catch-all DLQ packages.
Domain Kafka adapters live beside their domain; reusable contracts and infrastructure live under
`messaging/`.

## Architectural invariants

### Reservation inventory

- `ReservationTransactionService` locks the published accommodation row with `FOR UPDATE`.
- While holding that mutex, `ReservationRepositoryImpl` performs an overlap current read and then
  creates the reservation in the same transaction.
- There is no external distributed reservation lock, date-key locking scheme, or best-effort
  provisional inventory state.
- Redis remains in use for sessions, caches, recently viewed accommodations, and coupon stock; do
  not confuse those uses with reservation authority.

### Payment confirmation and cancellation

- Paid confirmation and cancellation are asynchronous `PaymentOperation` workflows.
- Durable states are `QUEUED`, `EXECUTING`, `WAITING_RETRY`, `APPLIED`, `DECLINED`, and
  `MANUAL_REVIEW`.
- API transactions lock the required accommodation/reservation rows and append a
  `PaymentOperationExecutionRequestedV1` outbox event.
- A worker claims one dispatch generation with a lease, invokes Toss Payments outside a DB
  transaction, and finalizes payment, ledger, reservation, coupon, audit, and search-refresh state
  atomically.
- Expired leases and due retries are recovered with a new generation. Unknown provider outcomes
  are inquired before another command is considered.
- Manual reconciliation is inquiry-only. There is no mark-paid endpoint; mark-not-paid is allowed
  only for an eligible confirmation with closed reason/evidence validation.
- See `docs/payment-operation-runbook.md` before changing failure or rollback behavior.

### Messaging and outbox

- Use `messaging.event.IntegrationEvent` and `EventDescriptor`; encode/decode with
  `IntegrationEventCodec`.
- Append via `messaging.outbox.application.OutboxWriter` inside the business transaction. It uses
  mandatory transaction propagation.
- Debezium publishes committed inserts with at-least-once delivery. Consumers must tolerate
  duplicate wake-up signals.
- The EventRouter transform is guarded by a `TopicNameMatches` predicate for
  `airbob_outbox.airbobdb.outbox`; heartbeat and connector metadata records bypass the transform.
- The fixed business streams are:
  - `PAYMENT_OPERATION.events`, `.RETRY`, `.DLT`
  - `ACCOMMODATION_INDEX.events`, `.RETRY`, `.DLT`
  - `OPERATOR_ALERT.events`, `.RETRY`, `.DLT`
- Topic auto-creation is disabled. `docker/kafka/init-topics.sh` and
  `docker/debezium/register-connector.sh` are deployment gates.
- Outbox row count and oldest-row age describe retention footprint, not delivery backlog. Check
  Connect tasks, heartbeat freshness, topic ingress, and consumer lag for delivery health.

### Search indexing

- All accommodation-related mutations publish `AccommodationSearchRefreshRequestedV1`.
- The event carries only the accommodation UID. `AccommodationSearchSnapshotReader` rebuilds the
  current document from MySQL after the Kafka transaction has ended.
- `AccommodationIndexingService` saves a full document only when currently `PUBLISHED`; otherwise
  it deletes the document.
- The `accommodations` alias must point to exactly one versioned write index. Alias bootstrap gates
  consumer startup; full reindex builds a new version index and atomically swaps the alias.
- See `docs/accommodation-indexing-operations.md` and `docs/logstash-reindex.md`.

### Durable operator alerts

- Payment manual-review transitions and payment/search DLT incidents append an operator-alert
  event transactionally.
- Alerts contain closed kind/summary values and safe source coordinates. Do not include payloads,
  payment keys, provider responses, credentials, or exception messages.
- Slack delivery has its own main/retry/DLT stream and must never recursively alert from its DLT.

## Database

- MySQL 8 with Flyway migrations under `src/main/resources/db/migration/`.
- Current schema history is V1 through V20.
- V17 introduced `payment_operation`; V18 established the canonical orchestration/outbox contract;
  V19-V20 added manual-resolution audit and reconciliation state.
- V16-V20 were prepared before the initial ETL, so this cutover assumes an empty business database.
  Do not rewrite them after deployment; later migrations must account for persisted data normally.

## Testing expectations

- Use real MySQL tests for lock ordering, overlap/current-read behavior, optimistic versions,
  transaction rollback, and migration constraints.
- Use Embedded Kafka for main → retry → DLT behavior and header sanitation.
- Testcontainers cover MySQL, Redis, and Elasticsearch where the behavior depends on the real
  implementation.
- Do not weaken `test`-profile Kafka/scheduling isolation to make a focused test pass.

## External integrations

- Toss Payments gateway: `domain/payment/service/gateway/`
- Google Maps geocoding: `geo/`
- AWS S3 and CloudFront: image delivery
- Elasticsearch: `search/`
- Slack: `messaging/alert/infrastructure/slack/`

## Authentication

- Redis-backed sessions are enforced by `SessionAuthFilter`.
- `UserContext` carries request-scoped member information.
- `/api/v1/admin/**` is protected by `AdminAuthInterceptor`, which re-checks active ADMIN role from
  MySQL.
