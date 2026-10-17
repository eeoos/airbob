# Operator alert operations

Operator alerts are durable integration events. A business transition appends an
`OPERATOR_ALERT_REQUESTED` event to the MySQL outbox, Debezium publishes it to
`OPERATOR_ALERT.events`, and the dedicated consumer delivers an allowlisted message to Slack.
Delivery failures move through `OPERATOR_ALERT.events.RETRY` and finally
`OPERATOR_ALERT.events.DLT`. The DLT handler never calls Slack or publishes another alert, so an
alert outage cannot create a recursive alert storm.

## Activation

The main application enables the listener and Slack delivery by default. The webhook resolves the
operator-alert-specific variable first and falls back to the existing deployment variable:

```text
OPERATOR_ALERT_SLACK_WEBHOOK_URL -> SLACK_WEBHOOK_URL -> empty
```

`application-test.yaml` explicitly disables the listener and Slack adapter. A non-production
environment without an operator sink should override `OPERATOR_ALERT_KAFKA_AUTO_STARTUP=false` and
`OPERATOR_ALERT_SLACK_ENABLED=false`; production normally needs no new variable while the existing
`SLACK_WEBHOOK_URL` is present.

`OPERATOR_ALERT_SLACK_CONNECT_TIMEOUT` defaults to `2s` and
`OPERATOR_ALERT_SLACK_READ_TIMEOUT` defaults to `10s`. When the listener is active, a disabled or
blank Slack configuration is a delivery failure. It is retried and retained in the dedicated DLT;
it is never acknowledged as delivered.

Kafka topic auto-creation must stay disabled. Provision the main, retry, and DLT topics with the
same partition count before enabling the listener.

## Privacy contract

The event payload contains only:

- an alert UUID and subject UUID;
- an allowlisted alert kind and summary code;
- an optional allowlisted source topic with non-negative partition and offset.

Never add payment keys, provider responses, exception messages, input payloads, user-entered text,
credentials, or webhook URLs. The retry producer decodes the strict event contract, writes a
canonical subject key, and copies only validated retry coordinates. Invalid values become a fixed
identifier-free poison value. Incoming keys, custom headers, exception headers, and payload fields
are not copied to retry or DLT records.

Logs contain only alert UUID, topic, partition, and offset. Metrics have no dynamic or sensitive
tags:

- `airbob.operator.alert.delivered`
- `airbob.operator.alert.failure`
- `airbob.operator.alert.dlt`

## Incident handling

1. Check the three counters and consumer lag for the main, retry, and DLT topics.
2. Verify listener activation and Slack configuration without printing the webhook value.
3. For Slack `429` or `5xx`, confirm the endpoint is healthy and wait for bounded Kafka retry.
   For persistent `4xx`, correct the webhook configuration before replaying.
4. Identify retained records by alert UUID and Kafka coordinates only. Do not paste record bodies
   or headers into tickets or chat.

## Replay

After correcting the cause, validate that a DLT value decodes as `OPERATOR_ALERT_REQUESTED` version
`1`. Republish that canonical value to `OPERATOR_ALERT.events` with `subject_uid` as its key. Do not
copy DLT headers; Kafka must create fresh retry coordinates.

The fixed poison value (`event_type=UNKNOWN`) is intentionally not replayable. Fix the producing
caller and enqueue a new allowlisted alert occurrence instead. Do not reconstruct an alert from a
raw exception or provider payload.

Repeated enqueue of the same kind, subject, and occurrence is safe. The alert-specific MySQL
adapter uses one atomic `INSERT ... ON DUPLICATE KEY` statement, so concurrent retries converge on
one outbox row. An unrelated event-ID collision remains an error rather than being treated as a
dedupe hit. An append inside a business transition joins that transaction and rolls back with it;
Kafka DLT callers use `OperatorAlertEnqueueService`, which starts the required transaction.
