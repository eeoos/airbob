---
paths:
  - "src/main/resources/db/migration/**/*"
  - "src/test/java/**/migration/**/*"
  - "scripts/preflight-reservation-inventory-cutover.sh"
---

# Database migrations

- MySQL 8 with Flyway migrations under `src/main/resources/db/migration/`.
- Roll forward only. Do not rewrite applied migrations, and do not automatically roll a V25+
  database back to a pre-inventory binary.
- Earlier cutovers required stopping older readers/writers and passing guards before specific
  versions (V25, V28, V29, V30, V31, V32). Their procedures live in
  `docs/operations/reservation-inventory-cutover.md`, `docs/operations/coupon-issuance-operations.md`,
  and `docs/operations/coupon-time-contract.md`; read them before touching data those migrations
  guard.

## Migration notes

- V25: zero-reservation hard-cutover guard. It is not a database-level writer fence.
- V28: removes only the quote expiry constraint and column; keeps quote snapshots and checkout links.
- V29: coupon issue units and an immutable `issue_strategy` (dropped in V31); renames
  `redis_stock_prepared_at` to `stock_prepared_at`; blocks unreconciled Redis coupon preparation.
- V30: normalizes coupon business timestamps to UTC (audit timestamps were already UTC).
- V31: finalizes surrogate-key REPEATABLE_READ issuance; drops the composite unit table and the
  `issued_quantity`/`issue_strategy` columns.
