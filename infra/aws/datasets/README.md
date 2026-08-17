# AWS performance-lab dataset releases

Dataset publication is a producer/admin responsibility outside the ephemeral
lab Terraform role. Publish every object below
`datasets/<datasetRelease>/`, verify it locally, and upload `manifest.json`
last as the completion marker. The lab selects both the release name and the
exact manifest SHA-256; it never searches for a latest release.

## Release layout

```text
datasets/<release>/
├── manifest.json
├── benchmark/
│   └── manifest.json
├── mysql/
│   ├── airbob.sql.zst
│   └── sha256.txt
└── elasticsearch/                 # search-enabled releases only
    └── snapshot-reference.json
```

`sha256.txt` is exactly `<dump-sha256>  airbob.sql.zst` followed by one
newline. The SQL dump is canonical. An RDS snapshot is an optional rebuild
cache and must be bound to the release with `DatasetRelease`, `DatasetRunId`,
`DumpSha256`, `FlywayVersion`, and `ManifestSha256` tags.

`pipeline-rehearsal` accepts the existing `nplus1-v1` producer identity and may
disable search. `evidence` requires `traffic-v1`, a search snapshot, database
and Elasticsearch fingerprints, and the causal-fence fields enforced by
`verify-dataset-release.sh`. Both kinds require the current application
Flyway version, currently V17. An older dump must be regenerated through the
producer; changing only its label or manifest is invalid.

`benchmark/manifest.json` is the immutable, secret-free workload input. The
wrapper records its fixed key and SHA-256 as `source.benchmarkManifestKey` and
`source.benchmarkManifestSha256`; `manifest.json` remains the completion marker
and is uploaded only after this artifact. A rehearsal manifest must satisfy the
same `nplus1-v1` invariants consumed by k6, including the benchmark account,
row capacities, target ids, and unique recently-viewed ids. The digest binds
the exact bytes, so consumers must not reformat the file after publication.

## Assemble a pipeline-rehearsal release

The V17 ETL producer hands off one directory with exactly these ten regular,
non-symlink files:

```text
PROVENANCE.txt
SHA256SUMS
airbob-production-seed.sql.gz
backend-migrations.sha256
benchmark-fixture.json
database-fingerprint.tsv
etl-code.sha256
release-metadata.txt
source.sha256
traffic-v1.json
```

`SHA256SUMS` contains exactly nine canonical, filename-sorted entries—one for
every file except itself. The source release metadata binds the `traffic-v1`
manifest, its canonical ETL dataset run id, Flyway V17, and the
`reset-flyway-v1-v17-etl-reseed-before-traffic` recovery contract. The traffic
migration digest is the digest of the successful `flyway_schema_history`
`version|script|checksum` stream; it is intentionally not the digest of
`backend-migrations.sha256`. The migration inventory remains independently
bound by `SHA256SUMS`. The provenance must select the `large` profile and
enable both benchmark and traffic fixtures. Its service schema, traffic seed,
anchor, validity window, and timezone must agree with `airbobdb` and the
traffic manifest rather than merely being present.

First restore the ETL dump into a writer-free local MySQL `airbobdb`, place the
server in the read-only/quiesced state required by the capture script, and
provide the connection values through `AIRBOB_DATASET_DB_*` environment
variables. Do not put the password on the command line.

```bash
export AIRBOB_DATASET_DB_HOST=127.0.0.1
export AIRBOB_DATASET_DB_PORT=3306
export AIRBOB_DATASET_DB_USER=airbob_attestor
export AIRBOB_DATASET_DB_NAME=airbobdb
export AIRBOB_DATASET_DB_QUIESCED=true
read -rs AIRBOB_DATASET_DB_PASSWORD
export AIRBOB_DATASET_DB_PASSWORD

infra/aws/scripts/capture-dataset-attestation.sh \
  /path/to/etl-release /secure/path/attestation.json
```

Then create a caller-owned output directory with mode `0700` and assemble the
pipeline-only release. Both supplied times are RFC3339 UTC timestamps. The
evaluation time must be no earlier than the attestation capture, while
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
  rehearsal-v17 \
  "$AIRBOB_DATASET_EVALUATION_TIME" \
  "$AIRBOB_DATASET_VALID_UNTIL_UTC"
```

The assembler snapshots its inputs into a private `<release>.incomplete`
directory, verifies the exact ETL inventory, checksums, provenance, V17
metadata, traffic manifest, and live-database attestation, and deterministically
converts the gzip SQL bytes to single-threaded zstd. It copies the benchmark
manifest byte-for-byte, writes `manifest.json` last, runs
`verify-dataset-release.sh`, and only then atomically renames the directory to
the final release name. Existing final or incomplete destinations are never
overwritten.

These two scripts are local-only. They do not call AWS, upload to S3, create an
RDS snapshot, or start the performance lab. Publication remains a separate,
explicit producer/admin action after the local release has been reviewed.

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
produce the same digest. The content fingerprint is the SHA-256 of one compact
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
benchmark key names or string values, duplicate coupon ids, stale Flyway
lineage, expired evaluation windows, checksum drift, and mismatched search
snapshot metadata. It validates a fixed finite schema and marker family; it is
not general DLP or proof that the data is representative.
