# Production-skew benchmark dataset

`production-skew-v1`과 그 4배 규모 companion인 `production-skew-large-v1`은 공개 숙소
source의 지역, 유형, 가격, 좌표, 수용 인원과 review
activity signal을 보존하면서 예약, 리뷰, 위시리스트, 결제 원장에 결정적인 상관 편중을
추가한 읽기 전용 benchmark world다. 이 데이터셋은 실제 Airbob 사용자나 운영 트래픽의
복제본이 아니다. 분포 수치는 versioned synthetic hypothesis이며, 성능 결과도
"production-inspired workload에서의 측정"으로만 표현해야 한다.

## Closed v2 integrity boundary

`world.tableRows` is an exact 16-key inventory:

```text
accommodation, address, occupancy_policy, accommodation_image,
accommodation_amenity, member, reservation, review, review_image,
wishlist, wishlist_accommodation, payment, payment_transaction,
accommodation_review_summary, daily_revenue_stats, accommodation_inventory_day
```

For a verified production-skew release, `world.fingerprints` is also closed. It contains the 16
final keys `final-accommodation`, `final-address`, `final-occupancy-policy`,
`final-accommodation-image`, `final-accommodation-amenity`, `final-member`,
`final-reservation`, `final-review`, `final-review-image`, `final-wishlist`,
`final-wishlist-accommodation`, `final-payment`, `final-payment-transaction`,
`final-review-summary`, `final-daily-revenue`, and `final-inventory`; the eight base keys
`base-accommodation`, `base-member`, `base-reservation`, `base-review`, `base-wishlist`,
`base-wishlist-accommodation`, `base-payment`, and `base-payment-transaction`; and the two
composites `final-world` and `base-world`. Missing or extra keys are invalid. Unverified normal
worlds use empty fingerprint/scope maps and are not releasable; every release consumer additionally
requires `verificationPassed=true`.

The earlier 11-table v2 shape is a pre-fix artifact, not a compatible v2 variant. Rebinding its
hashes or checksums does not repair it. Commit the Airbob independent verifier first, then generate
a new release ID from a clean V27 database and clean ETL checkout pinned to that Airbob
`backend_head`. Source verification, isolated restore verification, attestation, assembly, and
bootstrap must verify the same immutable bytes. Credentials are environment-only; Compose receives
owner-only secret-file paths, never password values in `.env`, arguments, artifacts, or logs.

> 현재 상태, 2026-08-27: release 및 evidence 계약은 구현 후보 상태다. reviewed full CSV로
> 만든 canonical release, AWS publication, isolated RDS restore, persistent RDS snapshot과
> baseline artifact는 아직 이 문서에 증명되지 않았다. 아래 표의 `미수집` 항목이 실제
> immutable artifact 링크와 digest로 교체되기 전에는 이 데이터셋을 canonical 또는
> published라고 부르지 않는다.

## Canonical evidence status

| Evidence | Required record | Current state |
|---|---|---|
| Full source inventory | reviewed inventory SHA-256 and aggregate calibration | 미수집 |
| ETL source release | release ID, exact 14-file inventory, 13-entry `SHA256SUMS` | 미수집 |
| Release tuple | manifest, validator, calibration, spec, qualification, dump, migration, schema and world fingerprints | 미수집 |
| Pre-publication restore | schema-version-4 `attestation/restore.json` | 미수집 |
| Immutable S3 release | exact `datasets/<release>/manifest.json` key, version and SHA-256 | 미수집 |
| Restored RDS | resource ID, exact MySQL patch, post-restore semantic attestation | 미수집 |
| Persistent snapshot | snapshot ID and tuple-bound promotion receipt | 미수집 |
| Read-model baseline | valid A/A and AB/BA/AB observation links | 미수집 |

The absence of a value is intentional. Do not substitute a smoke fixture, example manifest,
local test output or a manually recomputed checksum for any row in this table.

## What the profile controls

The profile separates source-calibrated facts from explicit assumptions.

| Axis | `production-skew-v1` contract | Classification |
|---|---|---|
| Location, accommodation type and price | Preserve the sanitized source cohort's joint structure | source-calibrated |
| Review fanout | Top 5% share 35-55%; zero-review share 15-35%, after coverage calibration | source-calibrated with bounded imputation |
| Reservation popularity | Top 1% receives 30%, cumulative top 5% 50%, cumulative top 20% 80% | domain assumption |
| Wishlist-link popularity | Cumulative top 5% receives 55%, cumulative top 20% 85% | domain assumption |
| Member activity | Top 20% receives 75% of reservation and active-wishlist activity | domain assumption |
| Reservation terminal status | confirmed 55%, payment pending 15%, cancelled 20%, expired 10% | domain assumption |
| Published review status | hot 95%, middle 85%, cold 70% | domain assumption |
| Reservation age | 0-30d 20%, 31-180d 35%, 181-730d 35%, older 10% | domain assumption |

The repeatable v1 budgets are 50,000 accommodations, 200,000 members, 2,500,000 reservations,
1,000,000 reviews, 400,000 active wishlists and 1,500,000 wishlist links. The large profile keeps
every distribution and fanout ceiling unchanged while multiplying those six budgets by four:
200,000 accommodations, 800,000 members, 10,000,000 reservations, 4,000,000 reviews, 1,600,000
active wishlists and 6,000,000 wishlist links. These are capacity and skew choices, not production
counts. Payment and transaction rows are derived from the reservation lifecycle and are verified
from the final database rather than claimed as an independent source fact.

The two profiles serve different evidence boundaries:

- `production-skew-v1` is the immutable release baseline and the cheaper repeated correctness/A-A
  corpus.
- `production-skew-large-v1` is the direct scale qualification corpus used to show that plan and
  latency improvements survive a four-times-larger world.

The large profile is not a v1 release alias. Its profile ID participates in deterministic seed
derivation, its tracked JSON has its own SHA-256, and its qualification must report
`canonicalScale=true`. Release, restore and AWS publication accept exactly two closed tuples:
`production-skew-v1` with `production-skew-v1.json` and the v1 budgets above, or
`production-skew-large-v1` with `production-skew-large-v1.json` and the four-times budgets above.
Every consumer derives the spec filename and budgets from the digest-bound profile and rejects a
third profile, mixed filename or checksum-rebound budget.

Missing review exports are not interpreted as zero demand. `source-calibration-v1.json` records
aggregate coverage and eligibility by source cohort. It contains no raw reviewer identity or review
prose. The release builder rejects raw PII sentinels, unapproved email shapes, credentials and
secret-like content before publication.

## Determinism and world boundaries

The same source inventory, selected profile spec, global seed, anchor and timezone must produce
the same logical fingerprints and target selection regardless of input order or batch size. The
contract fingerprints canonical domain rows, not compressed dump bytes or ETL timestamps.

Read-model target selection runs once in a writer-free, read-only REPEATABLE READ snapshot after
finalization and verification. Stable IDs and ascending dates are the final tie-breakers. A target
set selected in separate transactions or copied from a previous world is invalid even when each ID
still exists.

`benchmark-dataset-v2.json` carries `world-v2` and these world boundaries:

- `final-world`: all final service-table rows, including read-only overlays.
- `base-world`: only the production-skew base membership.
- `final-inventory`: the canonical final table inventory.
- distribution assertion: observed distribution and integrity evidence.
- `targetFingerprint`: the ordered read-model and search target definitions and expected results.

The service dump does not include ETL mapping tables. Restore therefore identifies base membership
through exactly eight non-empty contiguous `world.scopeRanges`:

```text
accommodation
member
payment
payment-transaction
reservation
review
wishlist
wishlist-accommodation
```

For every range, `id` must match its map key and
`rowCount == maximumId - minimumId + 1`. Missing, sparse, renamed or additional ranges invalidate
the release. The six generated base ranges must also equal the selected profile's exact budgets;
the corresponding final `tableRows` values must be greater than or equal to those budgets because
the enabled benchmark capsules add deterministic overlay rows.

## Release invariants

A release is publishable only when all of these statements are true in the restored MySQL world:

- The composite contract is `benchmark-dataset-v2` / `world-v2` on Flyway V27.
- `accommodation_inventory_day` has zero rows. This read-only world never starts inventory seeding.
- The outbox is empty at the publication and restore boundary.
- Review summary rows match PUBLISHED review truth symmetrically: no missing, stale or extra row.
- Wishlist `accommodation_count` and `representative_accommodation_id` match membership truth and
  `(created_at DESC, id DESC)` ordering symmetrically.
- `daily_revenue_stats` matches the UTC payment ledger symmetrically.
- Lifecycle and referential-integrity checks are green.
- Final, base, distribution, target and inventory fingerprints equal the release tuple.
- All 15 `read-model-v2` targets reproduce their manifest `expectedRows` and
  `expectedResultHash` from the database.

Any failure prevents manifest and dump publication. Rebinding checksums after changing a payload
does not repair a semantic mismatch.

## Read-model targets

The runner selects one `TARGET_ID`; manual accommodation IDs, member IDs, dates, page sizes and
accounts are forbidden.

| Query kind | Target IDs | Meaning of `expectedRows` |
|---|---|---|
| `REVIEW_SUMMARY_V1` | `review-hot`, `review-median`, `review-cold`, `review-empty` | PUBLISHED review count represented by the aggregate response |
| `WISHLIST_PAGE_V1` | `wishlist-hot`, `wishlist-median`, `wishlist-cold`, `wishlist-empty`, `wishlist-hot-deep` | rows returned by the manifest-bound page/cursor |
| `REVENUE_RANGE_V1` | `revenue-recent-1d`, `revenue-recent-7d`, `revenue-medium`, `revenue-broad`, `revenue-empty`, `revenue-refund-boundary` | UTC day rows returned by the range |

Wishlist targets bind dedicated ACTIVE MEMBER account references. Revenue targets bind one ACTIVE
ADMIN account reference. Passwords remain environment-only and never appear in the release or
evidence artifact. Result hashes use the manifest's canonical length-prefixed field stream; see the
[read-model benchmark guide](../../load-test/k6/read-model/README.md) for query-specific
normalization.

## Immutable release contents

The ETL producer emits exactly 14 top-level files:

```text
PROVENANCE.txt
SHA256SUMS
airbob-production-seed.sql.gz
backend-migrations.sha256
benchmark-dataset-v2.json
benchmark-fixture.json
database-fingerprint.tsv
etl-code.sha256
generation-qualification-v1.json
production-skew-v1.json | production-skew-large-v1.json  # exactly one, selected by profile
release-metadata.txt
source-calibration-v1.json
source.sha256
traffic-v1.json
```

`SHA256SUMS` has exactly 13 canonical entries. The selected spec occupies one entry; including both
spec files or neither is invalid. `release-metadata.txt` has the fixed v2 43-line contract and binds
that filename. `benchmark-fixture.json` deliberately remains the standalone `nplus1-v1` payload;
only the composite dataset and world advance to v2.

Airbob assembly converts the source release into this exact layout. The Elasticsearch reference is
present only for a search-enabled release.

```text
<release>/
├── manifest.json
├── attestation/
│   └── restore.json
├── benchmark/
│   ├── manifest.json
│   ├── dataset-manifest.json
│   ├── validate-benchmark-dataset-v2.jq
│   ├── source-calibration-v1.json
│   ├── production-skew-v1.json | production-skew-large-v1.json  # exactly one
│   └── generation-qualification-v1.json
├── mysql/
│   ├── airbob.sql.zst
│   ├── sha256.txt
│   └── database-fingerprint.tsv
└── elasticsearch/
    └── snapshot-reference.json
```

`manifest.json` schema version 2 binds the immutable tuple. Its `releaseTuple` includes artifact
digests plus final/base/distribution/target/inventory fingerprints. `attestation/restore.json`
schema version 4 binds the exact source dump to the imported database, Flyway/schema state,
canonical verifier output, denormalized truth and recomputed target results. It separately records
the live distribution-evidence digest, manifest distribution-assertion seal, and exact tracked
production-spec digest; omission or rebinding of either proof-chain seal is invalid.

## How to produce a release candidate

### Prerequisites

- Java 21, MySQL 8, `jq`, `gzip`, `zstd` and SHA-256 tooling.
- Clean ETL and Airbob Git worktrees. The producer rechecks both before publication.
- A reviewed full source inventory with at least 50,000 unique listings for
  `production-skew-v1`, or at least 200,000 for `production-skew-large-v1`. Preview/smoke source
  is not canonical input.
- An isolated, empty `airbobdb` migrated through V27 and an absent or exact-empty ten-table
  `airbob_etl` metadata schema.
- No application, scheduler, CDC, replication applier or other database writer.
- Separate one-shot restore and read-only attestor credentials for local attestation.

### 1. Generate and qualify in the ETL repository

Use the ETL runbook for database preparation. Supply secrets only through prompted environment
variables. The command accepts no positional arguments.

```bash
export AIRBOB_ETL_DB_URL='jdbc:mysql://127.0.0.1:3307/airbobdb'
export AIRBOB_ETL_DB_USER='root'
export AIRBOB_ETL_SOURCE_PATH=/absolute/path/to/reviewed-full-source
export AIRBOB_ETL_BACKEND_ROOT=/absolute/path/to/airbob
export AIRBOB_ETL_RELEASE_ROOT="$PWD/releases"
export AIRBOB_ETL_RELEASE_ID="production-seed-$(date -u +%Y%m%dT%H%M%SZ)"

read -rsp 'ETL database password: ' AIRBOB_ETL_DB_PASSWORD; echo
read -rsp 'Benchmark account password: ' AIRBOB_ETL_BENCHMARK_PASSWORD; echo
read -rsp 'Coupon account password: ' AIRBOB_ETL_COUPON_ACCOUNT_PASSWORD; echo
export AIRBOB_ETL_DB_PASSWORD AIRBOB_ETL_BENCHMARK_PASSWORD
export AIRBOB_ETL_COUPON_ACCOUNT_PASSWORD

./scripts/build-production-seed.sh

unset AIRBOB_ETL_DB_PASSWORD AIRBOB_ETL_BENCHMARK_PASSWORD
unset AIRBOB_ETL_COUPON_ACCOUNT_PASSWORD
```

The script fixes canonical scale, batch size `1000`, the selected closed release profile and a
12 GiB maximum heap. `AIRBOB_ETL_RELEASE_PROFILE` defaults to `production-skew-v1`; select
`production-skew-large-v1` explicitly for the four-times corpus. `generation-qualification-v1.json`
must prove the profile's exact generated budgets and all bounded retained maxima. Operators do not
write that receipt by hand.

Verification:

```bash
release="$AIRBOB_ETL_RELEASE_ROOT/$AIRBOB_ETL_RELEASE_ID"
test "$(find "$release" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d ' ')" = 14
test "$(wc -l < "$release/SHA256SUMS" | tr -d ' ')" = 13
jq -e '.schemaVersion == 2 and .datasetVersion == "benchmark-dataset-v2" and
  .world.version == "world-v2" and .world.flywayVersion == 27 and
  .world.tableRows.accommodation_inventory_day == 0' \
  "$release/benchmark-dataset-v2.json"
```

### 2. Capture the pre-publication restore attestation

Run from the Airbob repository against an isolated, empty MySQL target. The capture script verifies
the ETL inventory, imports the exact gzip, turns the target read-only, runs the canonical verifier
twice and publishes the output only when both passes are stable.

```bash
export AIRBOB_DATASET_ETL_REPOSITORY=/absolute/path/to/etl
export AIRBOB_DATASET_DB_HOST=127.0.0.1
export AIRBOB_DATASET_DB_PORT=3307
export AIRBOB_DATASET_DB_NAME=airbobdb
export AIRBOB_DATASET_DB_USER=airbob_attestor
export AIRBOB_DATASET_DB_RESTORE_USER=airbob_restore
export AIRBOB_DATASET_DB_QUIESCED=true

read -rsp 'Restore password: ' AIRBOB_DATASET_DB_RESTORE_PASSWORD; echo
read -rsp 'Attestor password: ' AIRBOB_DATASET_DB_PASSWORD; echo
export AIRBOB_DATASET_DB_RESTORE_PASSWORD AIRBOB_DATASET_DB_PASSWORD

infra/aws/scripts/capture-dataset-attestation.sh \
  /secure/path/to/etl-release \
  /secure/path/to/attestation.json

unset AIRBOB_DATASET_DB_RESTORE_PASSWORD AIRBOB_DATASET_DB_PASSWORD
```

The output must be outside the source release and must not already exist. Revoke the one-shot
restore grant after capture.

### 3. Assemble and verify

Use caller-owned mode-0700 directories and UTC timestamps inside the dataset validity window.

```bash
install -d -m 700 /secure/path/assembled
dataset_release="production-seed-$(date -u +%Y%m%dT%H%M%SZ | tr '[:upper:]' '[:lower:]')"
evaluation_time="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# Current ETL contract: 2027-08-01T00:00:00 Asia/Seoul.
valid_until_utc=2027-07-31T15:00:00Z

infra/aws/scripts/assemble-dataset-release.sh \
  /secure/path/to/etl-release \
  /secure/path/to/attestation.json \
  /secure/path/assembled \
  "$dataset_release" \
  "$evaluation_time" \
  "$valid_until_utc"

infra/aws/scripts/verify-dataset-release.sh \
  "/secure/path/assembled/$dataset_release" \
  "$dataset_release" \
  pipeline-rehearsal
```

The assembler independently derives and enforces the validity boundary from `traffic-v1.json`.
If the tracked ETL anchor or validity contract changes, derive `valid_until_utc` from that manifest
instead of retaining the value above.

### 4. Publish, restore and attest again

Publication requires the applied `airbob-dataset-publisher` role and the exact foundation bucket.
It writes `manifest.json` last and refuses overwrite.

```bash
: "${dataset_release:?set the verified assembled release name}"
: "${AIRBOB_AWS_ACCOUNT_ID:?set the applied foundation account ID}"
AWS_PROFILE=airbob-dataset-publisher AWS_REGION=ap-northeast-2 \
  infra/aws/scripts/publish-dataset-release.sh \
  "/secure/path/assembled/$dataset_release" \
  "$dataset_release" \
  pipeline-rehearsal \
  "airbob-performance-lab-dataset-$AIRBOB_AWS_ACCOUNT_ID"
```

The account ID and release name above are examples. A real run must use the active foundation
account, reviewed release ID and exact manifest SHA selected by Terraform.

The current assembler emits `releaseKind=pipeline-rehearsal`, including when an optional
Elasticsearch snapshot reference is supplied. It does not create the stricter `evidence` wrapper.
Do not relabel the JSON manually. A canonical evidence claim remains pending until a reviewed
producer path creates and validates that contract, or the project explicitly accepts the
pipeline-rehearsal tuple as the baseline source.

AWS bootstrap must verify the wrapper and downloaded validator before database or service
mutation. After restore it must recompute the same final/base/distribution/target/inventory tuple,
all 15 target results, denormalized reconciliation and zero-inventory invariant before writing a
data-ready receipt. No receipt means no readiness.

After a valid restore receipt, an authorized operator may promote the exact RDS instance to a
persistent snapshot:

```bash
infra/aws/scripts/promote-rds-snapshot.sh \
  /secure/path/release/manifest.json \
  /secure/path/data-bootstrap-receipt.json \
  airbob-example-rds \
  airbob-dataset-example-snapshot \
  /secure/path/snapshot-promotion.json
```

The source instance must be exactly `airbob-<receipt.runId>`. The command refuses an existing
output path and atomically publishes a mode-0600 promotion receipt only after the available,
encrypted snapshot repeats the source run and RDS resource ID in its promotion tags. Snapshot-mode
Terraform accepts only that tagged promotion contract in addition to the release/run/dump/Flyway/
manifest tuple; a manually tagged tuple without the promotion markers is rejected.

Do not run a write capsule before collecting read-model evidence. If any write experiment has run,
restore the immutable base snapshot again.

## Troubleshooting release gates

| Failure | Action |
|---|---|
| ETL inventory or `SHA256SUMS` is not exact | Discard the incomplete output and rebuild from clean reviewed inputs. Never add, remove or rebind a payload manually. |
| Source manifest passes checksums but fails semantic validation | Treat it as generator/release drift. Fix the producer and generate a new release ID. |
| The two read-only verifier passes differ | A writer or unstable database state exists. Keep traffic closed, restore an empty isolated target and recapture. |
| Assembly rejects evaluation/valid-until | Derive UTC validity from the source `traffic-v1.json`; do not extend the window. |
| Publisher refuses its role or repository grant | Assume the dedicated publisher role and revoke the real Elasticsearch writer grant before retrying. |
| AWS restore tuple differs from the source tuple | Do not create readiness or promote a snapshot. Remove the failed ephemeral target and restore from the immutable release again. |

## Promotion checklist

A release may be marked canonical only after an operator records all of the following:

- [ ] Reviewed full source inventory SHA-256 and aggregate-only calibration SHA-256.
- [ ] Clean ETL and Airbob commits and the generated qualification receipt.
- [ ] Exact release ID and immutable S3 `manifest.json` SHA-256.
- [ ] Dump, migration, schema, validator, spec, manifest and target fingerprints.
- [ ] Stable pre-publication schema-version-4 attestation.
- [ ] Restored RDS resource ID, exact MySQL patch and identical post-restore tuple.
- [ ] Zero outbox and zero `accommodation_inventory_day` rows.
- [ ] Immutable RDS snapshot promotion receipt.
- [ ] Valid baseline links in [MySQL baseline evidence](mysql-baseline-evidence.md).

See [AWS dataset release operations](../../infra/aws/datasets/README.md) for publication and
bootstrap details, [AWS performance lab](aws-performance-lab.md) for the environment boundary and
the [production-skew implementation plan](../plans/2026-08-27-001-perf-production-skew-benchmark-dataset-plan.md)
for the requirement trace.
