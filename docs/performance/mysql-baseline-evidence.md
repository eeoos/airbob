# MySQL read-model baseline evidence

This document is the acceptance record for the review-summary, wishlist-page and daily-revenue
read models on one immutable `production-skew-v1` RDS snapshot. It defines what must be collected
before proposing an index. It does not contain a valid baseline yet.

> Current evidence status, 2026-08-27: `PENDING`. No canonical full-source release, isolated AWS
> RDS rehearsal, A/A noise envelope, AB/BA/AB comparison or window-aligned AWS resource artifact is
> linked below. Repository tests and plan output are harness evidence, not performance evidence.

## Decision boundary

The baseline answers two questions:

1. Does each raw read path and its materialized read-model path return the same canonical business
   result for hot, median, cold and empty parameter classes?
2. Under a stable snapshot, how do latency, rows examined and estimate error differ between those
   paths?

The baseline does not choose index columns, create a Flyway migration or claim production impact.
Invisible-index exploration is a separate raw MySQL treatment on a separate clone after a valid
baseline. Any application-level index experiment and V28+ migration require a new evidence review
and implementation plan.

Review-rating skew, member-signup skew and revenue-amount skew may improve result realism, but they
are not the primary selectivity explanation for the three access paths measured here.

## Evidence identity

Every source observation has schema `read-model-evidence-v1` and exactly these top-level fields:

```text
schema_version
metadata
validity
parity
performance
mysql_evidence
```

The immutable identity below must be the same across all observations in one comparison.

| Identity | Required fields | Current value |
|---|---|---|
| Dataset release | release ID, `benchmark-dataset-v2`, `world-v2` | 미수집 |
| Source tuple | calibration SHA, spec SHA, manifest SHA, dump SHA, migration SHA, target fingerprint SHA | 미수집 |
| App build | 40-character commit, immutable image digest, build ID, `instance_count=1` | 미수집 |
| Database | clone ID, pre/post fingerprint, exact MySQL 8.0 patch | 미수집 |
| Statistics | optimizer, table/index statistics, histogram and ANALYZE receipt SHA-256 | 미수집 |
| Runtime isolation | scheduler, Kafka listener, inventory lifecycle and external side effects all false | 미수집 |
| AWS host shape | region, RDS class/storage, app instance type, load-generator instance type | 미수집 |
| Resource window | window-aligned CloudWatch artifact and SHA-256 | 미수집 |

The runner's snake-case `release_tuple` has exactly nine fields:

```text
release_id
dataset_version
world_version
source_calibration_sha256
production_skew_spec_sha256
dataset_manifest_sha256
dump_sha256
schema_migration_sha256
target_fingerprint_sha256
```

Passwords, tokens, account email, session IDs, raw SQL bind values and payment provider data must
not appear in source or aggregate artifacts. `manifest_target.account_ref` is the only account
identity retained.

## Target matrix

Run every target independently so a normalized statement digest cannot hide parameter-class
behavior.

| Domain | Target class | Target ID | Evidence status |
|---|---|---|---|
| Review | hot | `review-hot` | 미수집 |
| Review | lower median | `review-median` | 미수집 |
| Review | minimum positive | `review-cold` | 미수집 |
| Review | zero | `review-empty` | 미수집 |
| Wishlist | hot first page | `wishlist-hot` | 미수집 |
| Wishlist | median first page | `wishlist-median` | 미수집 |
| Wishlist | cold first page | `wishlist-cold` | 미수집 |
| Wishlist | empty first page | `wishlist-empty` | 미수집 |
| Wishlist | hot deep cursor | `wishlist-hot-deep` | 미수집 |
| Revenue | recent 1 day | `revenue-recent-1d` | 미수집 |
| Revenue | recent 7 days | `revenue-recent-7d` | 미수집 |
| Revenue | medium range | `revenue-medium` | 미수집 |
| Revenue | broad range | `revenue-broad` | 미수집 |
| Revenue | empty range | `revenue-empty` | 미수집 |
| Revenue | refund/date boundary | `revenue-refund-boundary` | 미수집 |

`expectedRows` is a business-result cardinality. For review summary it is the PUBLISHED review count
inside the aggregate, not the single SQL aggregate row. For wishlist it is the returned page size.
For revenue it is the number of UTC day rows.

## Runtime isolation

Use only the AWS `isolated-read` application:

```text
SPRING_PROFILES_ACTIVE=aws,traffic-benchmark
SPRING_PROFILES_INCLUDE=read-model-benchmark
```

`performance-lab` is not active in this runtime. The start contract explicitly disables every
listener/inventory/external-write flag. Before and after every measurement window, the runner sends
a fresh challenge digest to the token-protected runtime assertion endpoint. The response must bind
the provisioning run ID, resource-fence digest, runtime revision, app instance, and the exact active
profiles `aws,read-model-benchmark,traffic-benchmark`; its observed writer facts must be:

```json
{
  "scheduler_enabled": false,
  "kafka_listener_enabled": false,
  "inventory_lifecycle_enabled": false,
  "external_side_effects_enabled": false
}
```

The app must run as one instance. A static lifecycle JSON is not accepted. `read-model-benchmark`
endpoints and token must not be enabled on
the normal serving profile. The test window permits no application writer, CDC-applied mutation,
histogram change, manual SQL DML or runtime recompute endpoint.

## Experiment protocol

### 1. Inspect the plan

Plan mode is read-only. It validates the manifest target and shows the exact block order.

```bash
READ_MODEL_DISCOVERY_MODE=plan \
RUN_ID=read-model-20260827 \
RELEASE_ID=production-seed-20260827t000000z \
CLONE_ID=clone-a \
TARGET_ID=review-hot \
BENCHMARK_DATASET_MANIFEST=/secure/path/benchmark-dataset-v2.json \
  load-test/k6/read-model/run-aws-read-model-discovery.sh | jq .
```

For a read-model comparison, the emitted protocol is:

```text
AA / AA / AA / AB / BA / AB
```

Each label is one paired block. The three A/A pairs run the materialized `after` variant twice to
measure noise. Only after all p50/p95/p99 maximum absolute relative deltas fit
`AA_MAX_RELATIVE_DELTA` does the runner execute three crossed before/after pairs.

### 2. Freeze optimizer state

After data load, and after candidate creation when applicable, disable
`innodb_stats_auto_recalc`. Run one `ANALYZE TABLE` before any measurement. The runner analyzes:

```text
review
accommodation_review_summary
wishlist
wishlist_accommodation
accommodation_image
daily_revenue_stats
payment_transaction
reservation
```

Do not run another `ANALYZE TABLE`, create or update a histogram, or issue DML during the
measurement. `capture-optimizer-state.sql` records the exact MySQL patch, optimizer switch,
persistent-stat settings, table/index statistics, index definitions and allowlisted histograms.
Pre/post optimizer, statistics and histogram digests must be byte-identical for each window.

### 3. Run the isolated AWS protocol

The following files must be regular mode-0600 inputs:

- `RELEASE_TUPLE_JSON`
- `APP_BUILD_JSON` with exact runtime revision, instance ID and provisioning resource-fence digest
- `BENCHMARK_TOKEN_FILE`
- `BENCHMARK_ACCOUNT_PASSWORD_FILE` for wishlist and revenue targets

Use a `mysql_config_editor` login path. `MYSQL_PWD` is forbidden by the MySQL evidence capture.

```bash
: "${AIRBOB_AWS_ACCOUNT_ID:?set the applied foundation account ID}"
READ_MODEL_DISCOVERY_MODE=run \
RUN_ID=read-model-20260827 \
RELEASE_ID=production-seed-20260827t000000z \
CLONE_ID=clone-a \
TARGET_ID=review-hot \
BENCHMARK_DATASET_MANIFEST=/secure/path/benchmark-dataset-v2.json \
RELEASE_TUPLE_JSON=/secure/path/release-tuple.json \
APP_BUILD_JSON=/secure/path/app-build.json \
BENCHMARK_TOKEN_FILE=/secure/path/read-model-token \
MYSQL_LOGIN_PATH=airbob-benchmark \
BASE_URL=https://benchmark.example.invalid \
AWS_EVIDENCE_BUCKET="airbob-performance-lab-evidence-$AIRBOB_AWS_ACCOUNT_ID" \
AWS_EVIDENCE_PREFIX=measurements/read-model-20260827/read-model/review-hot \
  load-test/k6/read-model/run-aws-read-model-discovery.sh
```

The URL and bucket above demonstrate input shape only. A real run must use the isolated lab origin,
approved evidence bucket and active orchestration lease.

The measurement lease fencing-token digest and provisioning resource-fence digest are separate
evidence fields. The former remains inside the lease boundary; only the latter is sent to the app
and compared with its provisioned server value. They need not be equal. The app-host token must be
handed to the load generator through an approved non-logging SecureString path before a real run;
the repository does not automate that cross-host prerequisite.

For each window the runner:

1. Captures and exact-validates a fresh pre-window runtime assertion.
2. Verifies before/after HTTP contracts, business parity, `expectedRows` and canonical result hash
   before warmup or measurement.
3. Captures raw `EXPLAIN FORMAT=JSON` and `EXPLAIN ANALYZE FORMAT=TREE` outside headline latency.
4. Captures a pre-window extended table checksum and optimizer snapshot.
5. Runs warmup, then only `phase=measure` requests in the headline metric.
6. Isolates one Performance Schema event window and records digest, calls, timer, rows examined,
   rows sent and errors.
7. Captures post-window database and optimizer state, then a fresh post-window runtime assertion,
   and rejects any drift or stale/replayed challenge.
8. Assembles `read-model-evidence-v1`, aggregates complete pairs and uploads immutable summary
   artifacts with `If-None-Match: *`.

The runner does not currently attach CloudWatch time series to
`read-model-observations-v1`. Export a separate immutable, window-aligned AWS resource artifact and
link its SHA-256 in this document before accepting a baseline.

### 4. Capture the AWS resource envelope

At minimum, preserve the dashboard series already defined by the lab for every measurement window:

- RDS `CPUUtilization`, `DatabaseConnections` and `FreeableMemory`.
- RDS `CPUCreditBalance`, `CPUSurplusCreditBalance` and `CPUSurplusCreditsCharged` when the selected
  class exposes burst credits.
- Load-generator EC2 `CPUUtilization` and `NetworkOut`.

Record UTC window bounds, CloudWatch period/statistic, RDS identifier/class/storage, load-generator
ID/type and raw export SHA-256. A graph screenshot alone is not evidence. If credits drain, surplus
credits accrue, the load generator saturates or the series has gaps, mark the affected block
invalid.

## Validity rules

An observation is valid only when every rule below passes.

| Gate | Valid condition |
|---|---|
| Manifest parity | before hash = after hash = manifest hash; observed rows = expected rows |
| Requests | attempted > 0; successful = attempted; errors = 0; dropped iterations = 0 |
| Headline scope | only `phase=measure`; setup, login, ANALYZE and EXPLAIN excluded |
| Dataset | release, target, clone and app build match across the pair |
| Database | pre fingerprint = post fingerprint |
| Optimizer | optimizer, statistics and histogram SHA-256 unchanged |
| Auto statistics | `auto_statistics_recalculation_detected=false` throughout |
| Lifecycle | scheduler, Kafka, inventory and external effects all disabled |
| Pairing | unique window/event IDs and complete, non-duplicated roles per block |
| MySQL event | isolated statement calls equal measured calls; errors = 0 |
| A/A | all p50/p95/p99 deltas inside the declared noise limit |
| AWS resources | window-aligned series present and no credit/load-generator distortion |

An invalid source is not averaged with valid sources. The aggregator rejects malformed, mismatched,
incomplete, duplicate, secret-bearing and PII-bearing source artifacts. Any one of the following is
a hard invalidation:

- response, row-count or result-hash drift;
- HTTP/check failure, no requests or dropped iteration;
- pre/post database, table/index statistics or histogram drift;
- automatic statistics recalculation or an active writer lifecycle;
- release/target/clone/app mismatch;
- incomplete pair or reused statement window/event ID;
- unstable A/A envelope or distorted/missing AWS resource window.

## Required per-target results

Fill one row per target and retain the immutable artifact link. Do not collapse hot, median, cold
and empty classes into one digest average.

| Target ID | Expected rows/hash verified | Estimate vs actual | Rows examined/sent | p50 / p95 / p99 ms | AWS window | Verdict |
|---|---|---|---|---|---|---|
| `review-hot` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `review-median` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `review-cold` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `review-empty` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `wishlist-hot` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `wishlist-median` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `wishlist-cold` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `wishlist-empty` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `wishlist-hot-deep` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `revenue-recent-1d` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `revenue-recent-7d` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `revenue-medium` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `revenue-broad` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `revenue-empty` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |
| `revenue-refund-boundary` | 미수집 | 미수집 | 미수집 | 미수집 | 미수집 | PENDING |

For estimate error, report both the optimizer estimate and the `EXPLAIN ANALYZE` iterator actual.
Keep raw JSON and TREE text linked even when the parser produces a summary. Rows examined and sent
come from the isolated Performance Schema window, while latency comes from non-instrumented k6
`phase=measure` requests.

## Invisible-index follow-up

Index exploration is allowed only after an exact six-window/three-pair A/A artifact binds the same
release, target, query parameters, clone, app runtime, and stable database fingerprint, and its
p50/p95/p99 deltas fit the configured noise threshold. Candidate run preflight recomputes the live
clone fingerprint and rejects a different current database. Use a separate clone containing exactly
one matching invisible candidate. The current harness executes and publishes a raw MySQL
baseline/candidate capture. It does not collect or publish application k6 latency for this treatment
because the app's Hikari sessions do not inherit the MySQL client's session-level
`use_invisible_indexes` switch.

```bash
READ_MODEL_DISCOVERY_MODE=plan \
RUN_ID=review-index-20260827 \
RELEASE_ID=production-seed-20260827t000000z \
CLONE_ID=clone-review-candidate \
TARGET_ID=review-hot \
CANDIDATE_INDEX=idx_candidate_example \
INVISIBLE_INDEX_INVENTORY=/secure/path/invisible-index-inventory.json \
AA_NOISE_OBSERVATION=/secure/path/review-hot-aa-observations.json \
AA_MAX_RELATIVE_DELTA=0.10 \
BENCHMARK_DATASET_MANIFEST=/secure/path/benchmark-dataset-v2.json \
  load-test/k6/read-model/run-aws-read-model-discovery.sh | jq .
```

The candidate name is illustrative. The harness does not create or delete the index. Plan output
must report `protocol=RAW-EXPLAIN-ONLY`, `application_k6_latency_supported=false`,
`application_performance_publication_supported=false` and
`raw_evidence_publication_supported=true`.

Run mode needs the same candidate inputs plus the mode-0600 release tuple, exact app build,
benchmark token, MySQL login path, isolated HTTPS origin and exact evidence-bucket prefix. Although
the treatment query is direct MySQL, the runner still calls the live app assertion endpoint before
and after both baseline and candidate windows; absence of that assertion fails closed.

```bash
: "${AIRBOB_AWS_ACCOUNT_ID:?set the applied foundation account ID}"
READ_MODEL_DISCOVERY_MODE=run \
RUN_ID=review-index-20260827 \
RELEASE_ID=production-seed-20260827t000000z \
CLONE_ID=clone-review-candidate \
TARGET_ID=review-hot \
CANDIDATE_INDEX=idx_candidate_example \
INVISIBLE_INDEX_INVENTORY=/secure/path/invisible-index-inventory.json \
AA_NOISE_OBSERVATION=/secure/path/review-hot-aa-observations.json \
AA_MAX_RELATIVE_DELTA=0.10 \
BENCHMARK_DATASET_MANIFEST=/secure/path/benchmark-dataset-v2.json \
RELEASE_TUPLE_JSON=/secure/path/release-tuple.json \
APP_BUILD_JSON=/secure/path/app-build.json \
BENCHMARK_TOKEN_FILE=/secure/path/read-model-token \
MYSQL_LOGIN_PATH=airbob-benchmark \
BASE_URL=https://isolated-read.example.invalid \
AWS_EVIDENCE_BUCKET="airbob-performance-lab-evidence-$AIRBOB_AWS_ACCOUNT_ID" \
AWS_EVIDENCE_PREFIX=measurements/review-index-20260827/read-model/review-hot \
  load-test/k6/read-model/run-aws-read-model-discovery.sh
```

The runner creates the manifest-bound SELECT, records stable pre/post database fingerprints,
captures optimizer state with invisible indexes off and on, and invokes
`capture-explain-analyze.sh` for `index-baseline` and `index-candidate`. Statistics and histograms
must remain identical between treatments and across capture. It writes
`read-model-candidate-raw-evidence-v1` with `performance_claim=null`, then uploads it immutably with
`Retention=raw`.

This raw artifact contains JSON/TREE EXPLAIN and optimizer evidence, not an application-latency
comparison. If the candidate is absent from the structured chosen plan, the artifact is preserved
with `eligibility.status=not-chosen` and the command fails after upload. It cannot support a
follow-up performance proposal.

A future application-level index A/B needs an explicit connection/session treatment that proves
every measured app connection uses the intended optimizer switch. Design that mechanism in the
separate index plan; do not infer it from the raw client session.

## Troubleshooting evidence runs

| Failure | Action |
|---|---|
| Setup reports response/hash/row drift | Stop. Confirm release and `TARGET_ID`, then restore the bound snapshot. Do not warm or measure. |
| No Performance Schema event matches measured calls | Confirm statement consumers/instruments and window isolation. Discard the window rather than using an aggregate digest. |
| Pre/post fingerprint or optimizer digest differs | Mark the block invalid, identify the writer or statistics change and restore a clean clone. |
| A/A exceeds `AA_MAX_RELATIVE_DELTA` | Do not run or publish read-model A/B. Stabilize the host, RDS and load generator, then repeat A/A. |
| Candidate is not in the chosen plan | Keep the raw artifact as negative evidence. Do not report an improvement or proceed to migration design. |
| CloudWatch window is missing or credit-distorted | Exclude the block even when k6 and MySQL artifacts are otherwise valid. |

## Baseline acceptance record

The baseline becomes reviewable only when every field is linked to immutable evidence:

| Required record | Link / value |
|---|---|
| Canonical release ID and wrapper SHA-256 | 미수집 |
| Source inventory, calibration, spec and dataset SHA-256 | 미수집 |
| Dump, migration, schema and target fingerprint SHA-256 | 미수집 |
| RDS snapshot ID, resource ID, exact MySQL patch and class | 미수집 |
| App commit and immutable image digest | 미수집 |
| ANALYZE receipt and optimizer/statistics/histogram snapshots | 미수집 |
| Valid A/A observation for every target | 미수집 |
| Valid AB/BA/AB observation for every target | 미수집 |
| Window-aligned CloudWatch resource artifacts | 미수집 |
| Invalid/excluded run ledger with reasons | 미수집 |

Until this table is complete, the only defensible conclusion is: **the harness is candidate-ready;
the canonical MySQL baseline and index decision are pending**.

See [production-skew dataset](production-skew-dataset.md), the
[read-model runner guide](../../load-test/k6/read-model/README.md), the
[MySQL evidence capture guide](../../load-test/mysql/README.md) and the
[AWS performance lab](aws-performance-lab.md). Requirement rationale is in the
[production-skew implementation plan](../plans/2026-08-27-001-perf-production-skew-benchmark-dataset-plan.md).
