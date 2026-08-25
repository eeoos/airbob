# Reservation inventory cutover

## Boundary

V25–V27 introduce the reservation date-inventory contract:

- V25 rejects a database containing any `reservation` row, then adds the composite owner key.
- V26 creates `accommodation_inventory_day` and its ownership constraints.
- V27 adds the `(status, id)` index used by the bounded published-accommodation seed scan.

This is a hard cutover, not a reservation backfill. The database guard detects data at one
instant; it does not fence a V24 application, batch, migration job, or operator credential.

## Required deployment sequence

1. Stop public admission and every V24-or-older writer, including sidecar jobs and manual SQL.
2. Keep those writers stopped for the rest of the cutover.
3. If the V26 inventory table does not exist, require `SELECT COUNT(*) FROM reservation` to
   return `0` before Flyway. Later deployments with that table already present keep their data.
4. Require every `PUBLISHED` accommodation to have a nonblank, plausible IANA `time_zone_id`.
   The shape check accepts valid single-segment identifiers such as `CET`; Java performs the
   authoritative `ZoneId` validation.
5. Apply V25, V26, and V27 in order.
6. For a reservation-capable deployment, start the current application. Its startup bootstrap
   performs full Java `ZoneId` validation and seeds the booking horizon before readiness succeeds.
7. Open admission only after application health succeeds.

If reservation data exists, stop. Do not delete production reservations merely to satisfy the
guard and do not infer inventory ownership without a separately reviewed backfill plan.

## Failure and repair

- A V25 guard failure occurs before its permanent DDL. Resolve the preflight condition, run
  Flyway `repair`, and retry while admission remains closed.
- V25 and V26 are separate migrations. If V26 fails, V25 remains complete; repair the V26 cause,
  run Flyway `repair`, and resume without replaying V25.
- Once V25 has started, do not automatically restart or roll back to a pre-V25 writer. Keep
  admission closed and roll forward with the current binary that understands the V25 cutover,
  V26 inventory, and V27 index.

## Environment-specific enforcement

- OCI runs the reviewed `reservation-inventory-cutover-preflight` container after stopping the
  old app and before the Flyway container. Its zero-data gate runs only while the inventory table
  is absent; timezone validation remains active on every deployment.
- AWS performance labs accept only an immutable V27 dataset and schema fingerprint. The dataset
  producer applies V1–V27 to an empty schema before it inserts ETL fixtures; bootstrap verifies
  the inventory table, exact zero-row contract, and published-timezone shape before app startup.
  Because this is a read-only search/cache lab, `performance-lab` disables inventory startup,
  rolling seed, and retention. Its traffic allowlist excludes reservation mutations and any
  accidental inventory-dependent call fails closed with HTTP 503 / `R026`.
- Normal AWS and OCI application profiles reject disabled startup or rolling seed during context
  initialization. Setting the property to false cannot open readiness outside `test` and
  `performance-lab`. Their application readiness opens only after the full timezone and
  booking-horizon check succeeds.

These controls assume writer credentials and alternate deployment paths are operationally
restricted. Neither environment claims that the V25 SQL guard alone provides writer fencing.
