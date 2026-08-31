# AWS performance-lab dataset releases

Reservation inventory의 V25–V27 hard-cutover 전제는
[`docs/reservation-inventory-cutover.md`](../../../docs/reservation-inventory-cutover.md)에도 정리되어 있다.

Dataset publication is a local producer responsibility of the dedicated
`airbob-dataset-publisher` role, never the ephemeral lab Terraform role. The
role and its bucket policy are implemented in the foundation root but have not
been applied, and no dataset release or Elasticsearch snapshot has been
published. After applying that reviewed foundation change, publish every
object below `datasets/<datasetRelease>/`, verify it locally, and upload
`manifest.json` last as the completion marker. The lab selects both the release
name and the exact manifest SHA-256; it never searches for a latest release.

## Closed source contract and landing order

The current source contract accepts exactly 16 `world.tableRows` keys:
`accommodation`, `address`, `occupancy_policy`, `accommodation_image`,
`accommodation_amenity`, `member`, `reservation`, `review`, `review_image`, `wishlist`,
`wishlist_accommodation`, `payment`, `payment_transaction`, `accommodation_review_summary`,
`daily_revenue_stats`, and `accommodation_inventory_day`.

A verified production-skew source accepts exactly 26 fingerprint keys: one `final-*` component for
each of those 16 tables (using `final-review-summary`, `final-daily-revenue`, and
`final-inventory` for the final three semantic names), the eight `base-*` components for
accommodation, member, reservation, review, wishlist, wishlist-accommodation, payment, and
payment-transaction, plus `final-world` and `base-world`. Release paths require
`verificationPassed=true`; unverified normal worlds have empty fingerprints and scopes and are not
release inputs.

An 11-table pre-fix manifest or release remains invalid after any checksum or metadata rebinding.
Land the Airbob verifier commit first, then generate a fresh release ID from clean ETL and Airbob
worktrees and a newly migrated empty V27 database. The ETL provenance pins the exact Airbob
`backend_head`. Source verification, isolated restored-dump verification, attestation, assembly,
and bootstrap live verification must consume identical immutable source bytes. Passwords are
environment-only; local Compose receives only paths to distinct owner-only secret files.

## Release layout

```text
datasets/<release>/
├── manifest.json
├── attestation/
│   └── restore.json
├── benchmark/
│   ├── manifest.json                 # legacy workload input
│   ├── dataset-manifest.json         # benchmark-dataset-v2 world + capsules
│   ├── validate-benchmark-dataset-v2.jq
│   ├── source-calibration-v1.json
│   ├── production-skew-v1.json | production-skew-large-v1.json  # exactly one
│   └── generation-qualification-v1.json
├── mysql/
│   ├── airbob.sql.zst
│   ├── database-fingerprint.tsv
│   └── sha256.txt
└── elasticsearch/                 # search-enabled releases only
    └── snapshot-reference.json
```

`sha256.txt` is exactly `<dump-sha256>  airbob.sql.zst` followed by one
newline. The SQL dump is canonical. An RDS snapshot is an optional rebuild
cache and must be bound to the release with `DatasetRelease`, `DatasetRunId`,
`DumpSha256`, `FlywayVersion`, and `ManifestSha256` tags.

The current trusted producer emits only `pipeline-rehearsal`, carrying the exact
`benchmark-dataset-v2` / `world-v2` source tuple and optionally disabling search.
`verify-dataset-release.sh` rejects `evidence` before reading a wrapper because no reviewed exact
evidence producer exists. A search-enabled rehearsal must not be relabeled as evidence. Releases
require the current application Flyway version, currently V27. An older dump must be regenerated
through the producer; changing only its label or manifest is invalid.

`benchmark/manifest.json` is the immutable, secret-free workload input. The
wrapper records its fixed key and SHA-256 as `source.legacyBenchmarkManifestKey` and
`source.legacyBenchmarkManifestSha256`; `manifest.json` remains the completion marker
and is uploaded only after this artifact. A rehearsal manifest must satisfy the
same `nplus1-v1` invariants consumed by k6, including the benchmark account,
row capacities, target ids, and unique recently-viewed ids. The digest binds
the exact bytes, so consumers must not reformat the file after publication.

`benchmark/dataset-manifest.json` is additive and does not replace the legacy
manifest. It carries `benchmark-dataset-v2`, the deterministic `world-v2`
population envelope, observed distributions, and versioned experiment
capsules. The wrapper binds its exact bytes through
`source.benchmarkDatasetManifestKey` and
`source.benchmarkDatasetManifestSha256`. Assembly and restore fail closed when
the file is missing, its digest changes, its profile or V27 lineage conflicts
with the wrapper, any declared world table count differs from the attested
database, or `accommodation_inventory_day` is not exactly zero.

The public composite contains exactly four capsules:

1. `nplus1-v1`
2. `read-model-v2`
3. `index-query-v1`
4. `coupon-accounts-v1`

Search targets carry the complete bounds/price/occupancy request; untyped
fields, dates, and currently unused amenity/accommodation-type filters are not
accepted. The manifest row count remains the dataset truth; the production
search API retains Elasticsearch's default 10,000-hit reporting bound. Cache
targets bind the 200-key uniform and 80:20 hotset populations, and warm
measurement artifacts must prove prefetch coverage for every selected target
resource id.
The bulk capsule is metadata for an isolated local write fixture, not permission
to mutate the immutable AWS world.

## Assemble a pipeline-rehearsal release

The V27 ETL producer hands off one directory with exactly these fourteen regular,
non-symlink files:

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
production-skew-v1.json | production-skew-large-v1.json  # exactly one
release-metadata.txt
source-calibration-v1.json
source.sha256
traffic-v1.json
```

`SHA256SUMS` contains exactly thirteen canonical, filename-sorted entries—one for
every file except itself. The profile selects exactly one spec file: `production-skew-v1` binds
`production-skew-v1.json` and budgets 50k/200k/2.5m/1m/400k/1.5m, while
`production-skew-large-v1` binds `production-skew-large-v1.json` and budgets
200k/800k/10m/4m/1.6m/6m. A third profile, both spec files, a mixed filename, or rebound budgets
fail the exact inventory and semantic gates. The source release metadata binds the `traffic-v1`
manifest, its canonical ETL dataset run id, Flyway V27, and the
`reset-flyway-v1-v27-etl-reseed-before-traffic` recovery contract. The traffic
migration digest is the digest of the successful `flyway_schema_history`
`version|script|checksum` stream; it is intentionally not the digest of
`backend-migrations.sha256`. The migration inventory remains independently
bound by `SHA256SUMS` and must contain every migration from V1 through V27,
including the V18 canonical outbox/orchestration migration, V19–V20 manual
payment-resolution migrations, and V21–V27 reservation checkout/inventory
migrations. The provenance must select the `large` profile and
enable both benchmark and traffic fixtures. Its service schema, traffic seed,
anchor, validity window, and timezone must agree with `airbobdb` and the
traffic manifest rather than merely being present.

The exact 43-line `release-metadata.txt` additionally names `benchmark-dataset-v2.json` and
binds its SHA-256. The composite manifest must use the same upper-cased ETL
profile and the same anchor, validity window, and timezone as `traffic-v1`.
Its declared table-row subset is checked against the live database attestation,
so changing only the composite JSON or release label cannot create a valid
release.

Prepare a standalone local MySQL server with an existing but completely empty
`airbobdb` schema. Stop every application, ETL process, scheduler, and CDC
writer first, and do not use a primary with replicas, replication appliers, or
any other independent writer. `AIRBOB_DATASET_DB_QUIESCED=true` is the
operator's assertion of that isolation; it is not a discovery mechanism.

V25 is a zero-data reservation-inventory hard cutover. The producer must apply
V1–V27 to the empty schema before ETL reseeds any reservation fixture; V24 or
older application writers must remain stopped through that boundary. A final
dataset may contain ETL-created historical reservation rows, but the dump must
already have V27 lineage and the `accommodation_inventory_day` table. Neither
the migration guard nor the attestation is a database-level fence for an old
writer. Do not automatically roll a V27 dataset back to a V24 binary.

The capture command receives two distinct credentials. The one-shot
`airbob_restore` account may import the dump and set the global
`super_read_only` variable. The separate `airbob_attestor` account is
read-only. The script first proves that `airbobdb` has zero tables, views,
routines, events, and triggers on the selected server UUID. It privately copies
and rehashes the verified gzip, imports those exact decompressed bytes, then
sets `GLOBAL super_read_only=ON`. MySQL consequently must report both
`read_only=ON` and `super_read_only=ON` before and throughout all verifier and
capture queries. The restore secret is removed before the attestor or ETL
verifier is invoked.

`AIRBOB_DATASET_ETL_REPOSITORY` points at a local clone that contains the exact
forty-character `etl_head` recorded in `PROVENANCE.txt`; the verifier reads the
two approved SQL contracts from that commit with `git show`, so uncommitted ETL
working-tree bytes are not trusted. Do not put either password on the command
line.

```bash
export AIRBOB_DATASET_ETL_REPOSITORY=/path/to/etl/repository
export AIRBOB_DATASET_DB_HOST=127.0.0.1
export AIRBOB_DATASET_DB_PORT=3307
export AIRBOB_DATASET_DB_USER=airbob_attestor
export AIRBOB_DATASET_DB_RESTORE_USER=airbob_restore
export AIRBOB_DATASET_DB_NAME=airbobdb
export AIRBOB_DATASET_DB_QUIESCED=true
read -rs AIRBOB_DATASET_DB_RESTORE_PASSWORD
export AIRBOB_DATASET_DB_RESTORE_PASSWORD
read -rs AIRBOB_DATASET_DB_PASSWORD
export AIRBOB_DATASET_DB_PASSWORD

infra/aws/scripts/capture-dataset-attestation.sh \
  /path/to/etl-release /secure/path/attestation.json
unset AIRBOB_DATASET_DB_PASSWORD AIRBOB_DATASET_DB_RESTORE_PASSWORD
```

The scripts do not create the schema or either account, stop external writers,
or configure a standalone topology. The ETL restore handoff must supply the
reviewed one-shot restore grant, a restricted read-only attestor grant, and the
quiesce procedure before this command is used; do not substitute an application
or broad everyday administrator credential. Revoke the restore grant after a
successful capture. Capture and snapshot production must reuse the same host,
port, and MySQL server UUID, with both global read-only variables still on.

The generated schema-version-4 attestation records
`databaseRestoreMethod=gzip-to-empty-airbobdb-v2` and requires
`restoredDumpSha256` to equal `sourceDumpSha256`. This binds the database under
inspection to the exact verified gzip imported into the initially empty schema
on the recorded MySQL server UUID. It also binds the source ETL commit,
provenance verifier inventory and output, live final/base/distribution/target/
inventory fingerprints, the manifest distribution-assertion seal, the exact tracked
production-spec SHA-256, all 15 read-model target results, successful Flyway lineage, full schema
fingerprint, outbox state, and every table count across two stable read-only passes. A missing or
rebound assertion/spec digest is invalid.

For a database-only rehearsal, create a caller-owned output directory with
mode `0700` and assemble the release without a snapshot reference. Both
supplied times are RFC3339 UTC timestamps. The evaluation time must be no
earlier than the attestation capture, while
`valid-until` must be the exact, still-future UTC instant derived from the
source `traffic-v1.json` `validUntil` and `timezone`; callers cannot extend the
source dataset window. If the producer also supplies `validUntilInstant`, the
assembler requires it to equal that independently derived instant.

```bash
install -d -m 700 /secure/path/assembled-releases
: "${AIRBOB_DATASET_EVALUATION_TIME:?set a current RFC3339 UTC time after attestation capture}"
: "${AIRBOB_DATASET_VALID_UNTIL_UTC:?set the exact UTC conversion of source validUntil/timezone}"

infra/aws/scripts/assemble-dataset-release.sh \
  /path/to/etl-release \
  /secure/path/attestation.json \
  /secure/path/assembled-releases \
  rehearsal-v27 \
  "$AIRBOB_DATASET_EVALUATION_TIME" \
  "$AIRBOB_DATASET_VALID_UNTIL_UTC"
```

The assembler snapshots its inputs into a private `<release>.incomplete`
directory, verifies the exact ETL inventory, checksums, provenance, V27
metadata, both benchmark manifests, traffic manifest, and live-database
attestation, and converts the
gzip SQL bytes with single-threaded zstd. The resulting compressed bytes are
SHA-256-bound by the wrapper; cross-machine byte identity is not claimed
without the same reviewed toolchain. It copies both benchmark manifests
byte-for-byte, writes `manifest.json` last, runs
`verify-dataset-release.sh`, and only then atomically renames the directory to
the final release name. Existing final or incomplete destinations are never
overwritten.

The attestation capture and assembler are local-only. They do not call AWS,
upload to S3, create an RDS snapshot, or start the performance lab. Snapshot
production and publication are separate explicit operations described below.

## Produce a search snapshot

A search-enabled rehearsal uses the native Elasticsearch S3 repository rather
than copying Elasticsearch data directories. It requires the immutable
`infra-image-release-<full-commit>` artifact produced by the successful
`infra-images` workflow. Download its `infra-image-release.json` to a private
local path. The producer rejects a mutable tag or a local image that does not
retain the exact ECR digest selected by that artifact.

The source name `accommodations` must be a single managed write alias whose
only target matches `accommodations-v*` and has `is_write_index=true`. The
producer resolves and freezes that physical target, rejects alias drift during
production, and records both `logicalAlias` and `snapshotIndex` in the
version-2 snapshot reference. Bootstrap restores only `snapshotIndex` into a
new dataset-versioned physical index before atomically moving `logicalAlias`.

The local Elasticsearch service must run that selected image and keep the
producer S3 client pinned to Seoul. The repository compose file supplies the
required client setting:

```bash
export AIRBOB_INFRA_IMAGE_RELEASE=/secure/path/infra-image-release.json
export ELASTICSEARCH_IMAGE="$(jq -er '.images.ELASTICSEARCH_IMAGE' \
  "$AIRBOB_INFRA_IMAGE_RELEASE")"
docker compose pull elasticsearch
docker compose up -d --no-deps elasticsearch
```

The producer requires a temporary, exact writer grant. In the ignored
`infra/aws/foundation/terraform.tfvars`, set the one release that may write and
review a saved foundation plan before applying it with `admin-eeoos`:

```hcl
dataset_snapshot_writer_release = "rehearsal-v27"
```

That value grants only
`elasticsearch/releases/rehearsal-v27/*` plus the matching DynamoDB lease row
`airbob-dataset-snapshot/rehearsal-v27`. A null value maps both grants to the
unusable `__disabled__` target. Do not authorize two release prefixes at once.

Configure an AWS CLI role profile after the reviewed foundation change has
been applied; replace the MFA device ARN with the IAM user's actual device:

```ini
[profile airbob-dataset-publisher]
source_profile = admin-eeoos
role_arn = arn:aws:iam::942632789808:role/airbob-dataset-publisher
mfa_serial = <MFA-device-ARN>
region = ap-northeast-2
role_session_name = airbob-dataset-local
duration_seconds = 7200
```

Confirm that the profile resolves to
`arn:aws:sts::942632789808:assumed-role/airbob-dataset-publisher/...`, then run
the full reindex and producer against the writer-free restored MySQL database.
The reindex must use that exact attested host and port, not the unrelated Compose
`mysql` service. Its reviewed source commit must equal the `gitCommit` in the
selected infrastructure image release. Do not place the database password on
the command line:

```bash
export AWS_PROFILE=airbob-dataset-publisher
export AWS_REGION=ap-northeast-2
export AIRBOB_REGION=ap-northeast-2
export AIRBOB_AWS_ACCOUNT_ID=942632789808
export AIRBOB_DATASET_ETL_REPOSITORY=/path/to/etl/repository
export AIRBOB_DATASET_DB_HOST=127.0.0.1
export AIRBOB_DATASET_DB_PORT=3307
export AIRBOB_DATASET_DB_USER=airbob_attestor
export AIRBOB_DATASET_DB_NAME=airbobdb
export AIRBOB_DATASET_DB_QUIESCED=true
export AIRBOB_DATASET_ES_URL=http://127.0.0.1:9200
export AIRBOB_DATASET_ES_CONTAINER=elasticsearch
read -rs AIRBOB_DATASET_DB_PASSWORD
export AIRBOB_DATASET_DB_PASSWORD

export MYSQL_SOURCE_MODE=external
export MYSQL_HOST="$AIRBOB_DATASET_DB_HOST"
export MYSQL_PORT="$AIRBOB_DATASET_DB_PORT"
export REINDEX_SOURCE_COMMIT="$(jq -er '.gitCommit' "$AIRBOB_INFRA_IMAGE_RELEASE")"
export LOGSTASH_JDBC_URL='jdbc:mysql://host.docker.internal:3307/airbobdb?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true'
export LOGSTASH_JDBC_USER="$AIRBOB_DATASET_DB_USER"
export LOGSTASH_JDBC_PASSWORD="$AIRBOB_DATASET_DB_PASSWORD"
CONFIRM_INDEXING_CONSUMER_PAUSED=true \
CONFIRM_MYSQL_SOURCE_QUIESCED=true \
  scripts/reindex-accommodations.sh
unset LOGSTASH_JDBC_PASSWORD
export AIRBOB_DATASET_ES_QUIESCED=true

aws sts get-caller-identity
infra/aws/scripts/produce-elasticsearch-snapshot.sh \
  /path/to/etl-release \
  /secure/path/attestation.json \
  "$AIRBOB_INFRA_IMAGE_RELEASE" \
  rehearsal-v27 \
  /secure/path/snapshot-reference.json \
  /secure/path/snapshot-producer-receipt.json
unset AIRBOB_DATASET_DB_PASSWORD
```

The script runs Logstash with `--no-deps`, so it cannot silently start the
Compose MySQL and index a different database. It compares the published-row
count on the external source before and after loading, validates the new index,
and atomically creates or moves the single write alias. Only after this succeeds
is the local `accommodations` alias considered quiesced input for the snapshot
producer.

Only one producer may own a release. Before its first S3 inventory call, the
producer acquires the protected DynamoDB row
`airbob-dataset-snapshot/<release>` with a monotonic fencing token. A heartbeat
covers snapshot creation and verification, while the takeover deadline extends
beyond the temporary STS credential expiry so a crashed local Elasticsearch
process cannot overlap a replacement writer. The S3 prefix
`elasticsearch/releases/<release>/` must have no object versions or delete
markers before production; a failed partial production is not silently cleaned
or reused. The producer reruns the source ETL commit's approved database
verifiers, freezes the source index, installs only the temporary role
credentials in the Elasticsearch keystore, and registers the writable
`airbob-dataset-producer` repository. After a successful snapshot it removes
that writer and registers the same bucket and base path as
`airbob-dataset-readonly`. It then verifies the repository and exact snapshot
metadata, restores to a temporary index, compares MySQL/Elasticsearch document
membership, numeric IDs, and canonical document identity pairs, and compares
the source-index mapping/content fingerprints with the restored snapshot. A
document identity pair is the lowercase accommodation UUID, one tab, the
positive decimal accommodation ID, and one newline. MySQL supplies
`LOWER(BIN_TO_UUID(accommodation_uid)), id`; Elasticsearch supplies
`_id, _source.accommodationId`. Both complete TSV streams are byte-sorted with
`LC_ALL=C` before hashing. The producer captures and binds them before the
source freeze and again after snapshot verification, so changing only an ES
`_id` cannot pass as the same dataset. The ES content fingerprint proves snapshot
fidelity; it is not a field-by-field MySQL-to-ES projection proof. Finally it
records a stable S3 object-version inventory. Cleanup removes local
repositories, temporary credentials, the restore index, and the source write
block; it never deletes the remote snapshot prefix.

That inventory includes every object version and delete marker under the
release prefix. The foundation therefore configures no object expiration,
noncurrent-version expiration, or expired-delete-marker cleanup on the dataset
bucket. Its only lifecycle action aborts incomplete multipart uploads after
seven days, which cannot remove a completed version recorded by a seal. A
failed prefix containing any materialized version remains permanently
occupied and must not be recycled; choose a new release name after resolving
the producer failure.

After both local mode-`0600` reference and receipt hard links exist and the
final lease check still succeeds, the producer creates exactly one immutable
sibling seal at `elasticsearch/seals/<release>.json`. Its schema version is 1
and its only other fields are `datasetRelease`, `snapshot`,
`snapshotReferenceSha256`, `snapshotReceiptSha256`, and `createdAt`. The S3
write uses `If-None-Match: *`, AES256, and `application/json`, then reads back
the returned object version and byte-compares it before treating the outputs as
valid. The producer never deletes or overwrites a seal.

If local cleanup cannot be confirmed, the producer intentionally leaves the
lease owned until the credential-bound deadline. Inspect it without deleting
or manually releasing an unknown owner:

```bash
AWS_PROFILE=airbob-dataset-publisher AWS_REGION=ap-northeast-2 \
  infra/aws/scripts/orchestration-lease.sh status \
  airbob-performance-lab-orchestration-lease \
  airbob-dataset-snapshot/rehearsal-v27
```

The two mode-`0600` outputs are removed if seal creation or readback fails. A
successful seal preserves them even if the subsequent lease release reports an
error, because their hashes are already immutably bound in S3. Database-only
and search-enabled assembly are alternative final releases, not sequential
updates: the assembler never adds files to an existing final directory. To
publish search for `rehearsal-v27`, produce the snapshot first and then pass its
reference as the assembler's optional seventh argument:

```bash
infra/aws/scripts/assemble-dataset-release.sh \
  /path/to/etl-release \
  /secure/path/attestation.json \
  /secure/path/assembled-releases \
  rehearsal-v27 \
  "$AIRBOB_DATASET_EVALUATION_TIME" \
  "$AIRBOB_DATASET_VALID_UNTIL_UTC" \
  /secure/path/snapshot-reference.json
```

This creates a search-enabled `pipeline-rehearsal` release. The receipt stays
outside the release directory and is supplied only to the publisher. The
current assembler does not produce the stricter `evidence` release kind.

Before publishing the wrapper, revoke the native repository writer. Set
`dataset_snapshot_writer_release = null`, review and apply a second foundation
plan, and refresh the assumed-role session. The publisher inspects its own IAM
role before its first S3 call and refuses to run while any real
`elasticsearch/releases/<release>/*` mutation grant remains. This makes the
same repository read-only after production instead of relying only on operator
discipline. The immutable seal permanently burns that successful release's
writer activation: never delete the seal and never authorize that release as a
snapshot writer again.

## Publish an immutable release

Run publication locally with the assumed-role profile. A database-only release
omits the final receipt argument; a search-enabled release requires it:

```bash
AWS_PROFILE=airbob-dataset-publisher AWS_REGION=ap-northeast-2 \
  infra/aws/scripts/publish-dataset-release.sh \
  /secure/path/assembled-releases/rehearsal-v27 \
  rehearsal-v27 \
  pipeline-rehearsal \
  airbob-performance-lab-dataset-942632789808 \
  /secure/path/snapshot-producer-receipt.json
```

The publisher copies the exact finite release inventory into private staging
and reruns the full validator before any write. For a search-enabled release it
also binds the reference, manifest, and producer receipt, then regenerates the
complete S3 object-version inventory before the wrapper upload and immediately
before the completion marker. Payload objects are uploaded with AES256 and
no-overwrite semantics. The restore attestation, dump, DB fingerprint, checksum,
composite and legacy manifests, standalone validator, calibration, tracked spec,
qualification receipt, and optional snapshot reference are all published and
read back first. `manifest.json` is the last write. Phase 3 verifies the external
wrapper SHA, downloads the fixed digest-bound semantic artifacts with bounded
timeouts, and executes the verified validator before requesting any secret.

For dump bootstrap, Terraform allocates gp3 storage from the same closed profile contract:
20 GiB for `production-skew-v1` and 100 GiB for `production-skew-large-v1`. Snapshot bootstrap does
not override allocated storage and inherits the promoted snapshot's capacity.

Publication never repairs or replaces a completed release and never deletes a
remote object. A retry may reuse an incomplete prefix only when every existing
key has the exact staged bytes and there are no unexpected keys. An existing
completion marker succeeds only when the entire remote inventory and every
byte match; any mismatch, extra key, missing key, changed snapshot inventory,
or lost write race fails closed. On success, retain the emitted manifest S3 URI
and SHA-256 for the lab run.

The AWS bootstrap later registers only `airbob-dataset-readonly` against the
same snapshot base path and restores `accommodations` with global state
excluded. The ephemeral lab role has no permission to publish or modify the
snapshot repository.

## Canonical database fingerprints

After importing the dump into `airbobdb`, generate the Flyway checksum from
the tab-separated, headerless MySQL output of this query, including its final
newline:

```sql
SELECT installed_rank, COALESCE(version, '<NULL>'), description, type, script,
       COALESCE(checksum, '<NULL>'), success
FROM flyway_schema_history
ORDER BY installed_rank;
```

Generate the schema fingerprint from a fixed twelve-field union. Text fields
are hex encoded so tabs, newlines, and connection collation cannot change the
record shape; SQL `NULL` is the non-hex sentinel `<NULL>`. This binds columns,
indexes, primary/unique/foreign/check constraints, foreign-key actions, and
check clauses rather than only column definitions:

```sql
SELECT 'COLUMN', HEX(TABLE_NAME), HEX(COLUMN_NAME), HEX(CAST(ORDINAL_POSITION AS CHAR)),
       HEX(COLUMN_NAME), HEX(COLUMN_TYPE), HEX(IS_NULLABLE),
       COALESCE(HEX(CAST(COLUMN_DEFAULT AS CHAR)), '<NULL>'), HEX(EXTRA),
       COALESCE(HEX(COLLATION_NAME), '<NULL>'),
       COALESCE(HEX(CHARACTER_SET_NAME), '<NULL>'),
       COALESCE(HEX(GENERATION_EXPRESSION), '<NULL>')
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'airbobdb'
UNION ALL
SELECT 'INDEX', HEX(TABLE_NAME), HEX(INDEX_NAME), HEX(CAST(SEQ_IN_INDEX AS CHAR)),
       COALESCE(HEX(COLUMN_NAME), '<NULL>'), HEX(CAST(NON_UNIQUE AS CHAR)),
       COALESCE(HEX(COLLATION), '<NULL>'), COALESCE(HEX(CAST(SUB_PART AS CHAR)), '<NULL>'),
       HEX(NULLABLE), HEX(INDEX_TYPE), HEX(IS_VISIBLE), COALESCE(HEX(EXPRESSION), '<NULL>')
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = 'airbobdb'
UNION ALL
SELECT 'CONSTRAINT', HEX(tc.TABLE_NAME), HEX(tc.CONSTRAINT_NAME),
       COALESCE(HEX(CAST(kcu.ORDINAL_POSITION AS CHAR)), '<NULL>'),
       COALESCE(HEX(kcu.COLUMN_NAME), '<NULL>'), HEX(tc.CONSTRAINT_TYPE),
       COALESCE(HEX(CAST(kcu.POSITION_IN_UNIQUE_CONSTRAINT AS CHAR)), '<NULL>'),
       COALESCE(HEX(kcu.REFERENCED_TABLE_SCHEMA), '<NULL>'),
       COALESCE(HEX(kcu.REFERENCED_TABLE_NAME), '<NULL>'),
       COALESCE(HEX(kcu.REFERENCED_COLUMN_NAME), '<NULL>'), HEX(tc.ENFORCED), '<NULL>'
FROM information_schema.TABLE_CONSTRAINTS AS tc
LEFT JOIN information_schema.KEY_COLUMN_USAGE AS kcu
  ON kcu.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA
 AND kcu.TABLE_NAME = tc.TABLE_NAME
 AND kcu.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
WHERE tc.CONSTRAINT_SCHEMA = 'airbobdb'
UNION ALL
SELECT 'REFERENTIAL', HEX(TABLE_NAME), HEX(CONSTRAINT_NAME), '<NULL>', '<NULL>',
       HEX(UNIQUE_CONSTRAINT_SCHEMA), HEX(UNIQUE_CONSTRAINT_NAME), HEX(MATCH_OPTION),
       HEX(UPDATE_RULE), HEX(DELETE_RULE), HEX(REFERENCED_TABLE_NAME), '<NULL>'
FROM information_schema.REFERENTIAL_CONSTRAINTS
WHERE CONSTRAINT_SCHEMA = 'airbobdb'
UNION ALL
SELECT 'CHECK', HEX(tc.TABLE_NAME), HEX(cc.CONSTRAINT_NAME), '<NULL>', '<NULL>',
       HEX(cc.CHECK_CLAUSE), HEX(tc.ENFORCED), '<NULL>', '<NULL>', '<NULL>', '<NULL>', '<NULL>'
FROM information_schema.CHECK_CONSTRAINTS AS cc
INNER JOIN information_schema.TABLE_CONSTRAINTS AS tc
  ON tc.CONSTRAINT_SCHEMA = cc.CONSTRAINT_SCHEMA
 AND tc.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
 AND tc.CONSTRAINT_TYPE = 'CHECK'
WHERE cc.CONSTRAINT_SCHEMA = 'airbobdb';
```

Write the headerless, tab-separated result to a temporary file, then run
`LC_ALL=C sort` over the complete records. Hash the sorted file including its
final newline. Producers and the Phase 3 bootstrap must use this exact query,
encoding, and bytewise sort.

Record the SHA-256 values as `mysql.migrationChecksumSha256` and
`mysql.schemaFingerprintSha256`. Record exact row counts for every table used
as a restore gate, including `flyway_schema_history`, `outbox`, and
`accommodation`. The bootstrap independently regenerates both fingerprints
and every declared count before it writes a data-ready receipt.

For a search-enabled release, the cross-store ID fingerprint is the SHA-256 of
the newline-terminated decimal ids for every `PUBLISHED` accommodation, sorted
numerically. Elasticsearch ids come from `_source.accommodationId` and must
produce the same digest. This numeric membership digest remains separate from
the document identity pair digest. For the latter, MySQL emits
`LOWER(BIN_TO_UUID(accommodation_uid)), id` and Elasticsearch emits
`_id, _source.accommodationId`; encode each pair as lowercase UUID, tab,
positive decimal ID, newline, then byte-sort the complete records with
`LC_ALL=C`. The snapshot reference and producer receipt bind these as
`dbDocumentIdentityPairsSha256` and `esDocumentIdentityPairsSha256`; the
release manifest binds the same values as
`databaseDocumentIdentityPairsSha256` and
`elasticsearchDocumentIdentityPairsSha256`. Each DB/ES pair must be equal.
After restore, Phase 3 independently regenerates both pair streams and rejects
any mismatch before the `accommodations` write-alias cutover. The content
fingerprint is the SHA-256 of one compact
JSON `_source` value per restored document, with object keys sorted by `jq -S
-c`, then all lines sorted bytewise with `LC_ALL=C`. Generate these values from
the completed snapshot and record them in both the manifest and snapshot
reference. The Phase 3 bootstrap repeats the scroll and both sorts after
restore; a matching count and mapping alone are insufficient.

## Validation

```bash
infra/aws/scripts/verify-dataset-release.sh \
  /path/to/release <dataset-release> pipeline-rehearsal
```

Use `evidence` as the final argument for a measurement release. The validator
rejects missing or extra manifest keys, the enumerated secret markers in
benchmark key names or string values, a missing or malformed composite
benchmark manifest, composite/wrapper SHA or table-count drift, duplicate
coupon ids, stale Flyway lineage, expired evaluation windows, checksum drift,
and mismatched search snapshot metadata. It validates a fixed finite schema and marker family; it is
not general DLP or proof that the data is representative.
