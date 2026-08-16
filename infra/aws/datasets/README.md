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
Flyway version, currently V16. An older dump must be regenerated through the
producer; changing only its label or manifest is invalid.

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

Generate the schema fingerprint the same way from:

```sql
SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, COLUMN_TYPE, IS_NULLABLE,
       COALESCE(COLUMN_DEFAULT, '<NULL>'), EXTRA, COLLATION_NAME
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'airbobdb'
ORDER BY TABLE_NAME, ORDINAL_POSITION;
```

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
rejects missing or extra manifest keys, secret-bearing key names, duplicate
coupon ids, stale Flyway lineage, expired evaluation windows, checksum drift,
and mismatched search snapshot metadata. It validates a fixed finite schema;
it is not a general secret scanner or proof that the data is representative.
