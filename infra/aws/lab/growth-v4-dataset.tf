locals {
  growth_v4_required_payloads = toset([
    "airbob-growth.sql.gz", "migration-files.json", "before-fingerprint.json", "read-scenarios.json",
    "runtime-plan.json", "runtime-scenarios.json", "scenario-qualification.json", "consumer-manifest.json",
    "cache-qualification.json", "search-qualification.json", "final-reset-fingerprint.json", "query-catalog.json",
    "http-coverage.json", "measured-workload-targets.json", "preparation-tools.tar.gz", "tool-sources.json",
    "measurements.json", "SHA256SUMS.json", "profile.json", "etl-binaries.json",
  ])
  growth_v4_dataset_release_valid = !local.services_enabled || try(
    var.data_qualification_only && var.database_bootstrap == "dump" && var.rds_engine_version == "8.4.11" &&
    sha256(nonsensitive(data.aws_s3_object.dataset_manifest[0].body)) == var.dataset_manifest_sha256 &&
    toset(keys(local.dataset_manifest)) == toset([
      "schemaVersion", "releaseKind", "datasetRelease", "datasetRunId", "datasetScale", "releaseTuple",
      "source", "mysql", "couponPreparation", "kafka", "search", "artifacts",
    ]) &&
    local.dataset_manifest.schemaVersion == 4 && local.dataset_release_kind == "growth-v4-aws-qualification" &&
    local.dataset_manifest.datasetRelease == var.dataset_release &&
    can(regex("^korea-growth-v4-[0-9a-f]{16}$", local.dataset_manifest.source.datasetId)) &&
    can(regex("^${local.dataset_manifest.source.datasetId}-aws-r[1-9][0-9]{0,2}$", var.dataset_release)) &&
    can(regex("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$", local.dataset_manifest.datasetRunId)) &&
    toset(keys(local.dataset_manifest.source)) == toset(["datasetId", "consumerManifestSha256", "publicationReceiptSha256"]) &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.source.publicationReceiptSha256)) &&
    length(setsubtract(local.growth_v4_required_payloads, toset(keys(local.dataset_manifest.artifacts)))) == 0 &&
    length(local.dataset_manifest.artifacts) >= 20 && length(local.dataset_manifest.artifacts) <= 64 &&
    alltrue([for name, item in local.dataset_manifest.artifacts :
      can(regex("^[a-zA-Z0-9][a-zA-Z0-9_.-]+$", name)) &&
      toset(keys(item)) == toset(["key", "versionId", "sha256", "bytes"]) &&
      item.key == "datasets/${local.dataset_manifest.source.datasetId}/${name}" &&
      can(regex("^[A-Za-z0-9._~+/=-]+$", item.versionId)) && length(item.versionId) <= 1024 && !contains(["null", "None"], item.versionId) &&
      can(regex("^[0-9a-f]{64}$", item.sha256)) && floor(item.bytes) == item.bytes && item.bytes > 0 &&
      item.bytes <= (name == "airbob-growth.sql.gz" ? 1100000000 : 100000000)
    ]) &&
    toset(keys(local.dataset_manifest.mysql)) == toset([
      "engineVersion", "flywayVersion", "dumpKey", "dumpSha256", "migrationChecksumSha256",
      "schemaFingerprintSha256", "expectedTableRows", "timezone", "outboxPolicy",
    ]) &&
    local.dataset_manifest.mysql.engineVersion == "8.4.11" && local.dataset_manifest.mysql.flywayVersion == "28" &&
    local.dataset_manifest.mysql.dumpKey == "airbob-growth.sql.gz" && local.dataset_manifest.mysql.timezone == "UTC" &&
    local.dataset_manifest.mysql.outboxPolicy == "absent" && length(local.dataset_expected_table_rows) == 32 &&
    local.dataset_expected_table_rows.flyway_schema_history == 28 && local.dataset_expected_table_rows.outbox == 0 &&
    local.dataset_expected_table_rows.accommodation_inventory_day > 0 &&
    alltrue([for name, rows in local.dataset_expected_table_rows : can(regex("^[a-z][a-z0-9_]*$", name)) && rows >= 0 && floor(rows) == rows]) &&
    local.dataset_manifest.mysql.dumpSha256 == local.dataset_manifest.artifacts["airbob-growth.sql.gz"].sha256 &&
    local.dataset_manifest.source.datasetId == "korea-growth-v4-${substr(local.dataset_manifest.mysql.dumpSha256, 0, 16)}" &&
    local.dataset_manifest.mysql.migrationChecksumSha256 == local.dataset_manifest.artifacts["migration-files.json"].sha256 &&
    local.dataset_manifest.source.consumerManifestSha256 == local.dataset_manifest.artifacts["consumer-manifest.json"].sha256 &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.mysql.schemaFingerprintSha256)) &&
    local.dataset_manifest.releaseTuple == {
      datasetVersion            = "benchmark-dataset-v4"
      generatorVersion          = "korea-growth-v4"
      dumpSha256                = local.dataset_manifest.mysql.dumpSha256
      migrationChecksumSha256   = local.dataset_manifest.mysql.migrationChecksumSha256
      schemaFingerprintSha256   = local.dataset_manifest.mysql.schemaFingerprintSha256
      consumerManifestSha256    = local.dataset_manifest.source.consumerManifestSha256
      verificationRuntimeSha256 = local.dataset_manifest.artifacts["preparation-tools.tar.gz"].sha256
    } &&
    (local.dataset_manifest.datasetScale == "small-qualification" ? (
      local.dataset_expected_table_rows.accommodation <= 1000 && local.dataset_expected_table_rows.reservation <= 50000 &&
      local.dataset_manifest.artifacts["airbob-growth.sql.gz"].bytes <= 10000000 &&
      local.dataset_manifest.artifacts["preparation-tools.tar.gz"].bytes <= 20000000
      ) : local.dataset_manifest.datasetScale == "selected-two-million" &&
      local.dataset_expected_table_rows.accommodation == 15954 && local.dataset_expected_table_rows.member == 150131 &&
      local.dataset_expected_table_rows.reservation == 2000268 && local.dataset_expected_table_rows.wishlist == 300026 &&
      local.dataset_expected_table_rows.accommodation_inventory_day == 4392547
    ) &&
    (local.dataset_manifest.datasetScale == "small-qualification" ? local.dataset_manifest.search == { enabled = false } : local.growth_v4_search_valid) && length(local.dataset_manifest.couponPreparation) == 0 &&
    toset(keys(local.dataset_manifest.kafka)) == toset(["topics"]) &&
    toset(local.dataset_manifest.kafka.topics) == local.dataset_kafka_topics && length(local.dataset_manifest.kafka.topics) == 12,
    false,
  )
  growth_v4_search_valid = try(
    local.dataset_manifest.source.datasetId == "korea-growth-v4-778895bd2bd73be4" &&
    toset(keys(local.dataset_manifest.search)) == toset(["enabled", "snapshotRelease", "artifacts", "seal"]) &&
    local.dataset_manifest.search.enabled == true &&
    local.dataset_manifest.search.snapshotRelease == "korea-growth-v4-778895bd2bd73be4-search-r1" &&
    toset(keys(local.dataset_manifest.search.artifacts)) == toset([
      "manifest.json", "snapshot-reference.json", "snapshot-producer-receipt.json", "snapshot-seal.json",
      "source-proof.json", "mysql-current-fingerprint.json", "historical-inventory.json", "seal-publication.json",
    ]) &&
    alltrue([for name, item in merge(local.dataset_manifest.search.artifacts, { nativeSeal = local.dataset_manifest.search.seal }) :
      toset(keys(item)) == toset(["key", "versionId", "sha256", "bytes"]) &&
      item.key == (name == "nativeSeal" ? "elasticsearch/seals/${local.dataset_manifest.search.snapshotRelease}.json" : "datasets/${local.dataset_manifest.search.snapshotRelease}/${name}") &&
      can(regex("^[A-Za-z0-9._~+/=-]+$", item.versionId)) && length(item.versionId) <= 1024 && !contains(["null", "None"], item.versionId) &&
      can(regex("^[0-9a-f]{64}$", item.sha256)) && item.bytes > 0 && item.bytes < 20000 && floor(item.bytes) == item.bytes
    ]) &&
    local.dataset_manifest.search.artifacts["manifest.json"].sha256 == "69a27409e161ceb9ab8cf996e4d8cb78636e9a212f9166479af096e293fb1066" &&
    local.dataset_manifest.search.seal.sha256 == local.dataset_manifest.search.artifacts["snapshot-seal.json"].sha256,
    false,
  )
}
