---
paths:
  - "**/src/**/messaging/**/*"
  - "**/src/**/domain/payment/**/*"
  - "docker/kafka/**/*"
  - "docker/debezium/**/*"
---

# Messaging, outbox, and operator alerts

Operations: `docs/operations/outbox-operations.md` and
`docs/operations/operator-alert-operations.md`.

## Events and outbox

- Use `messaging.event.IntegrationEvent` and `EventDescriptor`; encode/decode with
  `IntegrationEventCodec`.
- Append via `messaging.outbox.application.OutboxWriter` inside the business transaction. Its
  implementation (`JpaOutboxWriter`) uses mandatory transaction propagation.
- Outbox rows are insert-only. The Debezium connector treats any other operation on the table as
  fatal (`table.op.invalid.behavior: fatal`).
- Debezium publishes committed inserts with at-least-once delivery. Consumers must tolerate
  duplicate wake-up signals.
- The EventRouter transform is guarded by a `TopicNameMatches` predicate for
  `airbob_outbox.airbobdb.outbox`; heartbeat and connector metadata records bypass the transform.
- Outbox row count and oldest-row age describe retention footprint, not delivery backlog. Check
  Connect tasks, heartbeat freshness, topic ingress, and consumer lag for delivery health.

## Topics and listeners

- Topic auto-creation is disabled. `docker/kafka/init-topics.sh` creates every main, retry, and DLT
  topic with the same partition count (3) and asserts it. `scripts/deploy-oci.sh` creates topics
  before Flyway and registers the Debezium connector after it (see `deploy-ci.md`).
- Adding a destination means changing `IntegrationEventDestination`, `init-topics.sh`, the
  listener's `autoCreateTopics = "false"`, and `MessagingDeploymentContractTest` together.
- Build listener container factories with `IntegrationEventKafkaListenerContainerFactoryBuilder`.
  It wires `PrimaryTopicHeaderSanitizingInterceptor`, which strips retry headers from primary topics.

## Durable operator alerts

- Payment manual-review transitions, manual-resolution actions, and payment/search/cache DLT
  incidents append an operator-alert event transactionally.
- DLT handlers append the alert in their own transaction and ack the DLT record only after it
  commits.
- Alerts contain closed kind/summary values and safe source coordinates. Do not include payloads,
  payment keys, provider responses, credentials, or exception messages.
- Slack delivery has its own main/retry/DLT stream and must never recursively alert from its DLT.
