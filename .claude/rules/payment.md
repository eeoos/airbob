---
paths:
  - "**/src/**/domain/payment/**/*"
---

# Payment confirmation and cancellation

Read `docs/operations/payment-operation-runbook.md` before changing failure or rollback behavior.

## Workflow

- Paid confirmation and cancellation are asynchronous `PaymentOperation` workflows.
- API transactions lock the reservation row before its inventory date rows (cancellation also locks
  the payment row) and append a `PaymentOperationExecutionRequestedV1` outbox event.
- A worker acquires the operation UID's MySQL execution fence on a dedicated JDBC connection,
  claims one dispatch generation with a lease, invokes Toss Payments outside a DB transaction,
  finalizes payment, ledger, reservation, coupon, audit, and search-refresh state atomically, and
  then releases the fence.
- Expired leases and due retries are recovered with a new generation. Unknown provider outcomes
  are inquired before another command is considered.
- When a record reaches the payment DLT, `PaymentOperationDltIncidentService` re-dispatches the
  quarantined generation if it still matches and appends an operator alert.

## Execution fence

- Invoke the fence only when no transaction is active. Inside a transaction it refuses with `P007`.
- A fair per-instance permit bounds fence connections before pool checkout. Hikari maximum pool
  size must be at least twice the configured fence concurrency so every permit retains one
  transaction connection. Startup rejects a smaller Hikari pool, and also rejects a fence network
  timeout that does not exceed the MySQL lock wait.
- `P007` means fence-protected work never started. Post-action lock/connection cleanup failures are
  logged with stable metadata and must not replace a committed result.

## Manual resolution

- Manual reconciliation is inquiry-only. There is no mark-paid endpoint; mark-not-paid is allowed
  only for an eligible confirmation with closed reason/evidence validation, and its transaction
  must commit while holding the same execution fence.
