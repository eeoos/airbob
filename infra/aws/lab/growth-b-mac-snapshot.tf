# The Mac import snapshot proves exact counts/DDL, without claiming a full
# content fingerprint. Historical import expiry is separate from this run.
variable "global_b_snapshot_source_mode" {
  type    = string
  default = "verified-global-b-snapshot"
  validation {
    condition     = contains(["verified-global-b-snapshot", "mac-snapshot-counts-ddl"], var.global_b_snapshot_source_mode)
    error_message = "Select the full-fingerprint or Mac counts/DDL snapshot contract explicitly."
  }
}

locals {
  growth_b_mac_snapshot_valid = !local.services_enabled || try(
    local.growth_b_snapshot_selected && var.database_bootstrap == "snapshot" &&
    var.rds_engine_version == "8.4.11" && var.rds_instance_class == "db.t3.small" &&
    var.global_b_snapshot_provenance.key == "datasets/${var.dataset_release}-mac-snapshots/${var.rds_snapshot_identifier}/source-${var.global_b_snapshot_provenance.sha256}.json" &&
    can(regex("^[0-9a-f]{64}$", var.global_b_snapshot_provenance.sha256)) &&
    var.global_b_snapshot_provenance.bytes > 0 && var.global_b_snapshot_provenance.bytes <= 20 * 1024 * 1024 &&
    data.aws_s3_object.growth_b_snapshot_provenance[0].version_id == var.global_b_snapshot_provenance.version_id &&
    sha256(base64decode(nonsensitive(data.aws_s3_object.growth_b_snapshot_provenance[0].body_base64))) == var.global_b_snapshot_provenance.sha256 &&
    local.growth_b_snapshot_provenance.schemaVersion == 1 &&
    local.growth_b_snapshot_provenance.kind == "global-b-mac-snapshot-source" &&
    local.growth_b_snapshot_provenance.state == "MAC_SQL_SNAPSHOT_SOURCE_VERIFIED" &&
    local.growth_b_snapshot_provenance.datasetId == var.dataset_release &&
    local.growth_b_snapshot_provenance.account == var.account_id && local.growth_b_snapshot_provenance.region == var.aws_region &&
    !local.growth_b_snapshot_provenance.fullDatasetValidated && !local.growth_b_snapshot_provenance.rowContentHashesVerified &&
    local.growth_b_snapshot_provenance.source.mysql == { version = "8.4.11", flywayVersion = 28, schema = "airbobdb" } &&
    local.growth_b_snapshot_provenance.source.application.image == var.app_image_reference &&
    local.growth_b_snapshot_provenance.source.application.mainCommit == var.bundle_commit &&
    local.growth_b_snapshot_provenance.source.runId == var.rds_snapshot_source_run_id &&
    local.growth_b_snapshot_provenance.source.rds.identifier == "airbob-${var.rds_snapshot_source_run_id}" &&
    local.growth_b_snapshot_provenance.source.rds.resourceId == var.rds_snapshot_source_resource_id &&
    var.run_id != var.rds_snapshot_source_run_id &&
    !contains(data.aws_db_instances.growth_b_snapshot_targets[0].instance_identifiers, "airbob-${var.rds_snapshot_source_run_id}") &&
    local.lab_contract.approved_rds_snapshot_identifier == var.rds_snapshot_identifier &&
    local.growth_b_snapshot_provenance.snapshot.identifier == var.rds_snapshot_identifier &&
    data.aws_db_snapshot.dataset[0].db_snapshot_arn == local.growth_b_snapshot_provenance.snapshot.arn &&
    data.aws_db_snapshot.dataset[0].db_instance_identifier == "airbob-${var.rds_snapshot_source_run_id}" &&
    data.aws_db_snapshot.dataset[0].status == "available" && data.aws_db_snapshot.dataset[0].snapshot_type == "manual" &&
    data.aws_db_snapshot.dataset[0].engine == "mysql" && data.aws_db_snapshot.dataset[0].engine_version == "8.4.11" &&
    data.aws_db_snapshot.dataset[0].encrypted && data.aws_db_snapshot.dataset[0].allocated_storage == 100 &&
    data.aws_db_snapshot.dataset[0].storage_type == "gp3" && data.aws_db_snapshot.dataset[0].iops == 3000 &&
    data.aws_db_snapshot.dataset[0].kms_key_id == local.growth_b_snapshot_provenance.snapshot.kmsKeyArn &&
    jsonencode(data.aws_db_snapshot.dataset[0].tags) == jsonencode(local.growth_b_snapshot_provenance.snapshot.tags) &&
    local.growth_b_snapshot_provenance.snapshot.restorePermissions == [] &&
    local.growth_b_snapshot_provenance.snapshot.sourceResourceId == var.rds_snapshot_source_resource_id,
    false,
  )
  growth_b_mac_snapshot_preparation_refs_valid = try(
    toset(keys(local.dataset_manifest.preparation)) == toset(["sourceMode", "receipt", "sourceProvenance", "restoreReceipt", "countsDdlReceipt", "rdsCaBundle"]) &&
    local.dataset_manifest.preparation.sourceMode == var.global_b_snapshot_source_mode &&
    local.dataset_manifest.preparation.sourceProvenance == {
      key    = var.global_b_snapshot_provenance.key, versionId = var.global_b_snapshot_provenance.version_id,
      sha256 = var.global_b_snapshot_provenance.sha256, bytes = var.global_b_snapshot_provenance.bytes
    } &&
    alltrue([for name in ["receipt", "restoreReceipt", "countsDdlReceipt"] : startswith(local.dataset_manifest.preparation[name].key, "data-bootstrap/${var.run_id}/")]),
    false,
  )
  growth_b_mac_snapshot_target_valid = try(
    local.growth_b_mac_snapshot_valid && local.growth_b_mac_snapshot_preparation_refs_valid &&
    local.growth_b_service_preparation.kind == "global-b-mac-snapshot-target" &&
    local.growth_b_service_preparation.state == "MAC_SNAPSHOT_TARGET_COUNTS_DDL_VERIFIED" &&
    local.growth_b_service_preparation.runId == var.run_id && local.growth_b_service_preparation.resourceFence == var.fencing_token &&
    local.growth_b_service_preparation.window.expiresAt == tonumber(var.expires_at) &&
    local.growth_b_service_preparation.sourceSha256 == var.global_b_snapshot_provenance.sha256 &&
    local.growth_b_service_preparation.restoreCanonicalSha256 == local.dataset_manifest.preparation.restoreReceipt.sha256 &&
    local.growth_b_service_preparation.countsCanonicalSha256 == local.dataset_manifest.preparation.countsDdlReceipt.sha256 &&
    local.growth_b_service_preparation.target.identifier == local.dataset_manifest.rds.identifier &&
    local.growth_b_service_preparation.target.resourceId == local.dataset_manifest.rds.resourceId &&
    local.growth_b_service_preparation.target.serverUuid == local.dataset_manifest.rds.serverUuid &&
    local.growth_b_service_preparation.target.resourceId != var.rds_snapshot_source_resource_id &&
    !local.growth_b_service_preparation.fullDatasetValidated && !local.growth_b_service_preparation.rowContentHashesVerified &&
    !local.growth_b_service_preparation.sqlReplayed,
    false,
  )
}
