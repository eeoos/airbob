# Payment-operation runbook

This runbook covers asynchronous payment confirmation after the operation API has accepted a request. The MySQL `payment_operation` row is the source of truth. Kafka delivery is a wake-up signal; duplicate delivery is expected and safe.

## Provisioning and deployment prerequisites

Provision these three topics before deploying the application:

- `PAYMENT_OPERATION.events`
- `PAYMENT_OPERATION.events.RETRY`
- `PAYMENT_OPERATION.events.DLT`

Use the platform-approved partition count, replication factor, retention, and `min.insync.replicas`. Give all three topics the same partition count. Disable broker auto-creation in production so a misspelled topic cannot become part of the workflow.

The main consumer group is `payment-operation-execution-group`. The application uses the reservation UID as the record key. Confirm that the Debezium route for outbox aggregate type `PAYMENT_OPERATION` publishes to `PAYMENT_OPERATION.events`.

Example provisioning template (replace every angle-bracket value with an approved environment value):

```bash
kafka-topics --bootstrap-server <brokers> --create --if-not-exists --topic PAYMENT_OPERATION.events --partitions <partition-count> --replication-factor <replication-factor> --config min.insync.replicas=<minimum-in-sync-replicas>
kafka-topics --bootstrap-server <brokers> --create --if-not-exists --topic PAYMENT_OPERATION.events.RETRY --partitions <partition-count> --replication-factor <replication-factor> --config min.insync.replicas=<minimum-in-sync-replicas>
kafka-topics --bootstrap-server <brokers> --create --if-not-exists --topic PAYMENT_OPERATION.events.DLT --partitions <partition-count> --replication-factor <replication-factor> --config min.insync.replicas=<minimum-in-sync-replicas>
```

Before opening traffic, verify that migration V17 is applied, all three topics exist with the intended configuration, the consumer group can read the main topic, and the recovery scheduler is running on at least one instance. Provider connect and read timeouts must both remain shorter than `payment.operation.lease-duration`.

## Health queries

Run these statements with a read-only database account. They expose operation and reservation identifiers only; do not extend them with provider request or response data.

### Counts by durable status

```sql
SELECT status, COUNT(*) AS operation_count
FROM payment_operation
GROUP BY status
ORDER BY status;
```

Trend `READY`, `EXECUTING`, `RETRY_WAIT`, `OUTCOME_UNKNOWN`, and `MANUAL_REVIEW`. A growing nonterminal count is more actionable than a momentary nonzero count.

### Expired execution leases

```sql
SELECT BIN_TO_UUID(operation_uid) AS operation_uid,
       reservation_id,
       attempt_count,
       lease_expires_at
FROM payment_operation
WHERE status = 'EXECUTING'
  AND lease_expires_at < UTC_TIMESTAMP(6)
ORDER BY lease_expires_at
LIMIT 100;
```

These rows must be recovered as outcome-unknown work. The next provider action is inquiry, not another confirmation. If the count persists across two scheduler intervals, inspect scheduler errors and database lock contention.

### Stale ready operations

```sql
SELECT BIN_TO_UUID(operation_uid) AS operation_uid,
       reservation_id,
       attempt_count,
       last_enqueued_at
FROM payment_operation
WHERE status = 'READY'
  AND last_enqueued_at < UTC_TIMESTAMP(6) - INTERVAL 10 SECOND
ORDER BY last_enqueued_at
LIMIT 100;
```

The recovery scheduler republishes these rows through the transactional outbox. If rows remain stale, check scheduler execution, outbox CDC lag, topic availability, and consumer-group lag in that order.

### Operations requiring manual review

```sql
SELECT BIN_TO_UUID(operation_uid) AS operation_uid,
       reservation_id,
       failure_code
FROM payment_operation
WHERE status = 'MANUAL_REVIEW'
ORDER BY id
LIMIT 100;
```

`MANUAL_REVIEW` keeps the reservation and inventory in payment processing. Do not change the operation, reservation, payment, ledger, or coupon tables directly. Resolve the provider outcome through the approved operator procedure, then use the dedicated administrative resolution capability when one exists. That capability is outside the current release.

## Alerts

Create alerts for both conditions below:

1. A record reaches `PAYMENT_OPERATION.events.DLT`. Include only the original topic, partition, offset, and operation UID.
2. An operation transitions to `MANUAL_REVIEW`. Include only the operation UID and the transition name.

Never attach Kafka payloads, HTTP request or response data, provider credentials, card or virtual-account details, exception messages, or the `failure_message` column. The persisted `failure_code` is bounded and sanitized and may be used only in the manual-review query.

For a DLT alert, first look up the operation in MySQL. An already terminal operation needs no replay. For a nonterminal operation, the database recovery scheduler remains the primary recovery path; do not blindly replay a quarantined record and do not perform payment compensation from the DLT handler.

## Incident response

### Growing `READY` or retry backlog

1. Verify database availability and that the recovery scheduler is completing runs.
2. Verify outbox CDC lag and the three topic configurations.
3. Check consumer-group lag and application error rates.
4. Confirm that the operation count begins falling after recovery publication. Duplicate commands are safe; direct database edits are not.

### Expired `EXECUTING` leases or response loss

1. Confirm the lease is expired using `UTC_TIMESTAMP(6)` and the query above.
2. Let recovery move the operation through outcome-unknown handling.
3. Verify that the gateway performs inquiry before any later confirmation attempt.
4. Escalate persistent ambiguity to manual review. Do not expire the reservation unless the provider result is an explicitly classified final decline.

### Manual-review response

1. Record the operation UID in the incident ticket.
2. Establish the provider outcome through the approved provider console or inquiry procedure without copying sensitive response data into chat, logs, or alerts.
3. Keep the reservation in payment processing until the money state is established.
4. Escalate to the payment owner for explicit resolution; never infer success or decline from Kafka delivery alone.

## Rollback boundary

Before the first accepted operation, an application rollback is safe after confirming that no `payment_operation` row exists and no payment-operation command is present in the outbox or Kafka topics.

After acceptance, an older binary is unsafe because it cannot finish the durable workflow. Stop new confirmations, keep the current consumer and recovery scheduler running, and drain or explicitly resolve every nonterminal operation (`READY`, `EXECUTING`, `RETRY_WAIT`, and `OUTCOME_UNKNOWN`) before deploying an older binary. `MANUAL_REVIEW` rows must also be explicitly resolved; they are not safe to abandon. Do not roll back V17 while any operation, operation-linked ledger row, or payment created by this workflow remains.

Cancellation behavior is outside this cutover and must remain on its existing path throughout rollback or recovery.
