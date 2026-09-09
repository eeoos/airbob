locals {
  growth_payload_names = toset([
    "airbob-growth.sql.gz", "migration-files.json", "before-fingerprint.json", "read-scenarios.json",
    "runtime-plan.json", "runtime-scenarios.json", "scenario-qualification.json", "consumer-manifest.json",
    "verification-runtime.zip", "publication-receipt.json",
  ])
  growth_dataset_release_valid = !local.services_enabled || try(
    var.data_qualification_only && var.database_bootstrap == "dump" && var.rds_engine_version == "8.4.11" &&
    sha256(nonsensitive(data.aws_s3_object.dataset_manifest[0].body)) == var.dataset_manifest_sha256 &&
    toset(keys(local.dataset_manifest)) == toset([
      "schemaVersion", "releaseKind", "datasetRelease", "datasetRunId", "releaseTuple", "source", "mysql",
      "couponPreparation", "kafka", "search", "artifacts",
    ]) &&
    local.dataset_manifest.schemaVersion == 3 && local.dataset_release_kind == "growth-aws-qualification" &&
    local.dataset_manifest.datasetRelease == var.dataset_release &&
    can(regex("^korea-growth-v3-[0-9a-f]{16}$", local.dataset_manifest.source.datasetId)) &&
    can(regex("^${local.dataset_manifest.source.datasetId}-aws(-r[1-9][0-9]{0,2})?$", var.dataset_release)) &&
    can(regex("^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8}$", local.dataset_manifest.datasetRunId)) &&
    toset(keys(local.dataset_manifest.source)) == toset(["datasetId", "consumerManifestSha256", "publicationReceiptSha256"]) &&
    toset(keys(local.dataset_manifest.artifacts)) == local.growth_payload_names &&
    alltrue([for item in values(local.dataset_manifest.artifacts) :
      toset(keys(item)) == toset(["sha256", "bytes"]) && can(regex("^[0-9a-f]{64}$", item.sha256)) &&
      item.bytes > 0 && item.bytes < 100000000 && floor(item.bytes) == item.bytes
    ]) &&
    local.dataset_manifest.source.consumerManifestSha256 == local.dataset_manifest.artifacts["consumer-manifest.json"].sha256 &&
    local.dataset_manifest.source.publicationReceiptSha256 == local.dataset_manifest.artifacts["publication-receipt.json"].sha256 &&
    toset(keys(local.dataset_manifest.mysql)) == toset([
      "engineVersion", "flywayVersion", "dumpKey", "dumpSha256", "migrationChecksumSha256", "schemaFingerprintSha256",
      "expectedTableRows", "timezone", "outboxPolicy",
    ]) &&
    local.dataset_manifest.mysql.engineVersion == var.rds_engine_version && local.dataset_manifest.mysql.flywayVersion == "27" &&
    local.dataset_manifest.mysql.dumpKey == "airbob-growth.sql.gz" && local.dataset_manifest.mysql.timezone == "UTC" &&
    local.dataset_manifest.mysql.outboxPolicy == "absent" && length(local.dataset_expected_table_rows) == 32 &&
    local.dataset_expected_table_rows.flyway_schema_history == 27 && local.dataset_expected_table_rows.outbox == 0 &&
    local.dataset_expected_table_rows.accommodation_inventory_day == 0 &&
    alltrue([for key, value in local.dataset_expected_table_rows : can(regex("^[a-z][a-z0-9_]*$", key)) && value >= 0 && floor(value) == value]) &&
    local.dataset_manifest.mysql.dumpSha256 == local.dataset_manifest.artifacts["airbob-growth.sql.gz"].sha256 &&
    local.dataset_manifest.source.datasetId == "korea-growth-v3-${substr(local.dataset_manifest.mysql.dumpSha256, 0, 16)}" &&
    local.dataset_manifest.mysql.migrationChecksumSha256 == local.dataset_manifest.artifacts["migration-files.json"].sha256 &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.mysql.schemaFingerprintSha256)) &&
    local.dataset_manifest.releaseTuple == {
      datasetVersion            = "benchmark-dataset-v3"
      generatorVersion          = "korea-growth-v3"
      dumpSha256                = local.dataset_manifest.mysql.dumpSha256
      migrationChecksumSha256   = local.dataset_manifest.mysql.migrationChecksumSha256
      schemaFingerprintSha256   = local.dataset_manifest.mysql.schemaFingerprintSha256
      consumerManifestSha256    = local.dataset_manifest.source.consumerManifestSha256
      verificationRuntimeSha256 = local.dataset_manifest.artifacts["verification-runtime.zip"].sha256
    } &&
    length(local.dataset_manifest.couponPreparation) == 0 &&
    local.dataset_manifest.search == { enabled = false } &&
    toset(keys(local.dataset_manifest.kafka)) == toset(["topics"]) &&
    toset(local.dataset_manifest.kafka.topics) == local.dataset_kafka_topics && length(local.dataset_manifest.kafka.topics) == 12,
    false,
  )
}

locals {
  growth_bootstrap_data_command = local.services_enabled ? join("\n", [
    "set -euo pipefail",
    "umask 077",
    "install -d -m 700 /opt/airbob/bootstrap-helpers",
    join("\n", [for helper in ["bootstrap-growth-entry.sh", "bootstrap-growth-aws.py", "growth_aws_contract.py", "restore-growth-dataset.py", "validate-growth-dataset-v3.py"] : join("\n", [
      "cat > /opt/airbob/bootstrap-helpers/${helper}.gz.b64 <<'AIRBOB_GROWTH_HELPER'",
      base64gzip(file("${path.module}/../scripts/${helper}")),
      "AIRBOB_GROWTH_HELPER",
      "base64 --decode /opt/airbob/bootstrap-helpers/${helper}.gz.b64 | gzip --decompress > /opt/airbob/bootstrap-helpers/${helper}",
      "printf '%s  %s\\n' '${filesha256("${path.module}/../scripts/${helper}")}' /opt/airbob/bootstrap-helpers/${helper} | sha256sum --check --status",
      "chmod 700 /opt/airbob/bootstrap-helpers/${helper}",
    ])]),
    "export AIRBOB_REGION='${var.aws_region}'",
    "export AIRBOB_RUN_ID='${var.run_id}'",
    "export AIRBOB_DATASET_BUCKET='${local.lab_contract.dataset_bucket_name}'",
    "export AIRBOB_EVIDENCE_BUCKET='${local.lab_contract.evidence_bucket_name}'",
    "export AIRBOB_DATASET_RELEASE='${var.dataset_release}'",
    "export AIRBOB_DATASET_MANIFEST_SHA256='${var.dataset_manifest_sha256}'",
    "export AIRBOB_DATABASE_BOOTSTRAP='${var.database_bootstrap}'",
    "export AIRBOB_QUALIFICATION_ONLY='${var.data_qualification_only}'",
    "export AIRBOB_SNAPSHOT_SOURCE_RUN_ID='${var.rds_snapshot_source_run_id}'",
    "export AIRBOB_SNAPSHOT_SOURCE_RESOURCE_ID='${var.rds_snapshot_source_resource_id}'",
    "export AIRBOB_QUALIFICATION_KEY='${try(data.aws_db_snapshot.dataset[0].tags.DataBootstrapKey, "")}'",
    "export AIRBOB_QUALIFICATION_VERSION_SHA256='${try(data.aws_db_snapshot.dataset[0].tags.DataBootstrapVersionIdSha256, "")}'",
    "export AIRBOB_QUALIFICATION_SHA256='${try(data.aws_db_snapshot.dataset[0].tags.DataBootstrapSha256, "")}'",
    "export AIRBOB_RDS_ENDPOINT='${module.rds[0].address}'",
    "export AIRBOB_RDS_RESOURCE_ID='${module.rds[0].resource_id}'",
    "export AIRBOB_RDS_ENGINE_VERSION='${var.rds_engine_version}'",
    "export AIRBOB_DEBEZIUM_CONNECTOR_VERSION='${jsondecode(file("${path.module}/../images/release.json")).artifacts.debezium.version}'",
    "export AIRBOB_RDS_MASTER_SECRET_ARN='${module.rds[0].master_secret_arn}'",
    "export AIRBOB_DEBEZIUM_SECRET_ARN='${aws_secretsmanager_secret.debezium[0].arn}'",
    "export AIRBOB_ELASTICSEARCH_IMAGE_DIGEST='${split("@", var.infra_image_references["ELASTICSEARCH_IMAGE"])[1]}'",
    "export AIRBOB_COUPON_LUA_FILE=/opt/airbob/bootstrap-helpers/coupon_prepare.lua",
    "/opt/airbob/bootstrap-helpers/bootstrap-growth-entry.sh",
  ]) : ""
}
