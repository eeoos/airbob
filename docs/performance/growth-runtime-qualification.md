# Growth dataset: disposable runtime qualification

The ETL repository creates the historical dataset and restores an independent clone for live
coupon, review, wishlist, recently-viewed and reservation-expiration cases. This application
change supplies only the missing expiration entry point and makes its BEFORE implementation
restore consumed coupons in the same transaction as reservation state and history.

## Expiration entry point

`POST /api/v2/admin/benchmarks/bulk-write/reservation-expiration/execute` accepts
`{"variant":"BEFORE"}` or `{"variant":"AFTER"}` and returns the processed count. It requires:

- the `bulk-write-benchmark` profile;
- `benchmark.bulk-write.enabled=true` and `benchmark.bulk-write.external-fixture-enabled=true`;
- the existing `X-Benchmark-Token` access guard;
- the existing disposable-database readiness guard.

The external runner must prepare and own the fixture. The endpoint creates no data and rejects
more than 32 eligible expired reservations. Run it serially with scheduling and competing
writers disabled: BEFORE reports the eligible count observed before invoking the domain service,
so this count is not a concurrency measurement. The current request's user context is restored
even when cleanup fails; generated history uses the system actor.

The BEFORE SQL expectation is now `N` history inserts, `2N` updates (reservation and coupon)
and one selection, or `1 + 3N` Hibernate statements. This counter does not include the existing
JDBC inventory operations. The k6 assertions and observation aggregator use the same definition.
The AFTER implementation keeps its bulk coupon restoration and history insertion.

## Explicit AWS qualification admission

Ordinary `aws` and every `oci` execution remain rejected by the bulk-write guard. A future AWS
runtime runner may explicitly enable the bounded qualification only when all of these match:

- profiles `aws`, `performance-lab`, `test`, `growth-runtime-qualification` and
  `bulk-write-benchmark`;
- `benchmark.bulk-write.aws-qualification-enabled=true`;
- schema `airbob_growth_bulk_write_benchmark`, with the required tables;
- a canonical `AIRBOB_RUN_ID` and the actual JDBC connection's Seoul RDS endpoint
  `airbob-RUN_ID.<suffix>.ap-northeast-2.rds.amazonaws.com:3306`;
- certificate-verified TLS (`sslMode=VERIFY_IDENTITY`), with no conflicting or unknown URL
  properties;
- MySQL `CURRENT_USER()` equal to `growth_` plus the first 16 lowercase hexadecimal characters
  of SHA-256 of the run ID, followed by `@%`;
- explicit `false` for Kafka listener startup (general, search, cache and alerts), Toss,
  Google API, S3 writes and Slack delivery.

The runner must provision that temporary MySQL account with privileges only on the clone,
keep the restored base unchanged, and remove both account and clone afterward. The guard checks
connection and account identity; it does not inspect MySQL grants. No new profile silently enables
this path, and no endpoint performs account creation or database cloning.

The AWS provisioning/execution adapter and a published image containing this hook are separate
prerequisites. Passing these local tests is not evidence of AWS runtime writes, CDC, ALB routing,
autoscaling or throughput.

## Validation

MySQL 8.4.11 integration tests cover BEFORE SQL counts, used-coupon restoration, repeated cleanup
and rollback of coupon, inventory, reservation and history changes after an injected failure.
Controller and guard tests cover admission, fixture limits and user-context restoration. The
external ETL qualification exercises eight expired reservations per variant, exactly-once
history, restored coupons, a reclaimed-hold control and a repeated cleanup processing zero rows.

`ReadModelRuntimeAssertionController` belongs to the older read-model runner. Neither the ETL
generator nor this expiration hook depends on it; retain it until those older consumers migrate.
