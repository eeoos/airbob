# Growth dataset v3: small integration path

The ETL producer owns generation and the sealed MySQL dump. This checkout provides an explicit
v3 validator, empty-database restore command and k6 reader. The legacy v2 fixed-budget pipeline
retains its own format. Do not rename the growth dataset to a production-skew v2 profile.

## Qualification scope

`SMALL_LOCAL_SCENARIOS` means the producer ran migration, generation, independent restore, query
boundaries and disposable runtime scenarios. It does not mean an AWS deployment, RDS snapshot
promotion, throughput benchmark or autoscaling experiment completed.

The common dump contains closed historical rows and a labelled `query-boundaries-v1` cohort.
The historical activity cutoff is independent of the current runtime clock. Inventory, coupons
and active reservations are prepared in a disposable run database. Passwords and sessions are
provided at runtime; the contract contains only synthetic account identities.

## Validate and restore

```bash
bash infra/aws/scripts/verify-dataset-release.sh \
  /absolute/path/to/release korea-growth-v3-DUMP_PREFIX growth-small-integration

python3 infra/aws/scripts/restore-growth-dataset.py \
  --release /absolute/path/to/release \
  --expected-id korea-growth-v3-DUMP_PREFIX \
  --mysql-version 8.4.11 \
  --migration-dir src/main/resources/db/migration \
  --defaults-file /private/path/mysql-client.cnf \
  --database airbobdb \
  --receipt /private/path/restore-receipt.json
```

Use the actual dataset ID from `consumer-manifest.json`. The caller creates the target database;
the restore command refuses a nonempty database before importing. Configure server address,
TLS and credentials in the private MySQL defaults file. The command creates no AWS resources
and does not discover, drop or overwrite an existing application database.

The validator checks exact v3 semantics, engine, V1–V27 source hashes, artifact hashes, scenario
qualification and targets. Restore checks the target engine and then every table's exact DDL
and row count. The ETL integration runner additionally compares all rows with the original
JDBC fingerprint. A count/DDL receipt alone is not a claim that full row parity was recomputed.

## Read with k6

Set `GROWTH_RELEASE_DIR`, `EXPECTED_DATASET_ID`, `EXPECTED_MYSQL_VERSION=8.4.11`, `BASE_URL`
and `AIRBOB_ETL_BENCHMARK_PASSWORD` in the process environment. Avoid placing credentials in
command arguments or the release. The password must match the synthetic accounts in that run.

```bash
k6 run load-test/k6/test/benchmark-dataset-v3-test.js
k6 run --summary-export /private/path/read-summary.json \
  load-test/k6/traffic/growth-dataset-read.js
```

The first script checks accepted and rejected contract cases. The second prepares real sessions
and runs each sealed target once, checking HTTP status, semantic response hash and ordered IDs.
Session cookies stay in k6 memory. Anonymous targets explicitly clear the previous cookie state.
This is a compatibility run with one VU, not an ASG traffic profile.

`runtime-plan.json` describes fresh preparation requirements. `runtime-scenarios.json` is evidence
from an already disposed run; its campaign IDs must not be reused. The ETL runner exercises
the existing coupon, recently-viewed, review and wishlist APIs against the same v3 base identities.
The v2 coupon/read-model/traffic consumers do not automatically accept this format.

## Minimal domain hook and existing benchmark code

`ReservationExpirationBenchmarkController` only invokes domain cleanup; it does not generate
data. It requires `bulk-write-benchmark`, an explicit
`benchmark.bulk-write.external-fixture-enabled=true`, the existing benchmark token and the
existing disposable-schema guard. The external qualification fixture is limited to 32 expired
reservations. It is not enabled in an ordinary application profile.

The BEFORE expiration implementation now restores consumed coupons in the same transaction.
Its SQL baseline therefore includes one coupon update per target, in addition to reservation
updates and history inserts. The AFTER path retains bulk restoration and history insertion.
MySQL 8.4.11 integration tests cover the updated counts and mid-transaction rollback.

`ReadModelRuntimeAssertionController` remains a process/environment assertion used by the older
AWS read-model runner. The new generator and restore path do not depend on it. An ASG rollout
still needs the AWS owner's multi-instance readiness/observation contract; this small local path
does not silently relax the older single-instance assertions.

## Before AWS execution

The explicit publisher now accepts `growth-small-integration` after the shared publisher role,
MFA trust, revoked ES writer and region gates. Set `GROWTH_PUBLICATION_RECEIPT` to a new private
file. It publishes only the seven consumer artifacts and `consumer-manifest.json`, with that
completion marker written last. Every object is create-only and read back by its exact S3
VersionId. `fetch-growth-dataset-v3.py` consumes the receipt plus an independently supplied
manifest SHA-256 and downloads into a new directory. It never selects a latest object version.
The old v2 bootstrap does not accept this new completion marker automatically.

On 2026-09-09 the small release was published to the project dataset bucket, and the lab operator
successfully fetched and validated all eight pinned objects. This is S3 transport evidence only;
RDS restore and AWS application execution remain pending. Publication/fetch failure tests cover
17 cases, and the existing v2 publisher tests still pass.

## AWS qualification envelope

This integration checkout combines the AWS infrastructure workstream with the explicit growth
consumer tools. It does not contain the consumer worktree's Java fixture hook or its newer query
implementation. Application compatibility must be checked against a separately published image.

`assemble-growth-aws-release.py` binds the eight original S3 consumer files and their publication
receipt to the exact locally qualified ETL verifier binaries, profile and V1–V27 migrations.
Its separate `growth-aws-qualification` manifest retains the source dataset ID and uses a new
`DATASET_ID-aws` prefix. The existing source release is never rewritten. The wrapper publisher
writes ten payloads before its `manifest.json` completion marker and reads every exact S3 version
back. The verifier package contains no password or AWS credential.

`aws-lab.sh prepare` accepts this wrapper only with dump bootstrap, exact MySQL 8.4.11 and
application capacity disabled. Before acquiring the creation lease, it validates all payloads,
the sealed verifier and current migration hashes. The regular lease, deadline, private-network
probe, failure cleanup and TTL controls apply. The RDS restore uses certificate-verified TLS,
refuses a nonempty database and runs the sealed JDBC verifier to compare every table's entire
row fingerprint and DDL with the original. No session/password reset, inventory seeding, coupon
preparation or CDC connector registration occurs before qualification.

Successful execution writes versioned `growth-full-verification.json` and
`dataset-qualification.json` under that run's evidence prefix. Merely assembling or publishing
the wrapper does not produce these receipts. The source consumer manifest intentionally retains
`awsExecutionValidated=false`; AWS proof is a separate immutable artifact. The current growth
gate refuses app launches and snapshot reuse until those runtime contracts are connected.

For this RDS qualification, the existing immutable service images may supply the private runner.
Their Debezium version is not certified for MySQL 8.4 by this experiment. CDC, AWS application
queries, throughput and ALB/ASG scaling require their own runs and results.
