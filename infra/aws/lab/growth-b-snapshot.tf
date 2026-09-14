# B snapshot restores are separate from both SQL preparation and legacy V27
# promotion. The first stage creates only RDS + a preparation host; the snapshot
# adapter must verify the actual target before normal service admission.
variable "global_b_snapshot_restore_only" {
  type        = bool
  default     = false
  description = "Create the explicit B snapshot target without SQL import, CDC, app ASG or ALB."
  validation {
    condition = !var.global_b_snapshot_restore_only || (
      !var.global_b_prepare_only && !var.global_b_services && !var.app_enabled && !var.load_generator_enabled &&
      var.mode == "performance" && var.dns_mode == "direct-only" && var.database_bootstrap == "snapshot" &&
      var.deployment_phase != "data-ready" && var.global_b_snapshot_provenance != null &&
      can(regex("^global-growth-b-[0-9a-f]{16}$", var.dataset_release))
    )
    error_message = "B snapshot target creation requires its own provenance and no import, normal-service, app or load transition."
  }
}

variable "global_b_snapshot_provenance" {
  type        = object({ key = string, version_id = string, sha256 = string, bytes = number })
  default     = null
  description = "Exact S3 reference for the separate B snapshot provenance; never a V27 promotion receipt."
}

locals {
  growth_b_snapshot_selected = var.global_b_snapshot_restore_only || (var.global_b_services && var.database_bootstrap == "snapshot")
  growth_b_snapshot_bucket   = try(startswith(var.global_b_snapshot_provenance.key, "data-bootstrap/"), false) ? local.lab_contract.evidence_bucket_name : local.lab_contract.dataset_bucket_name
  growth_b_snapshot_provenance = local.services_enabled && local.growth_b_snapshot_selected ? try(
  jsondecode(base64decode(nonsensitive(data.aws_s3_object.growth_b_snapshot_provenance[0].body_base64))), null) : null
  growth_b_snapshot_core = try(local.growth_b_snapshot_provenance.contract, null)
  growth_b_snapshot_tool_sources = { for name in ["growth_b_aws_restore.py", "growth_b_aws_contract.py", "growth_b_contract.py",
  "growth_b_runtime.py", "growth_b_inventory.py", "growth_b_snapshot.py"] : name => filesha256("${path.module}/../scripts/${name}") }
  growth_b_snapshot_valid = var.global_b_snapshot_source_mode == "mac-snapshot-counts-ddl" ? local.growth_b_mac_snapshot_valid : local.growth_b_legacy_snapshot_valid
  growth_b_legacy_snapshot_valid = !local.services_enabled || try(
    local.growth_b_snapshot_selected && var.database_bootstrap == "snapshot" && var.rds_engine_version == "8.4.11" &&
    (var.global_b_snapshot_provenance.key == "datasets/${var.dataset_release}-aws-snapshots/${var.rds_snapshot_identifier}/provenance-${var.global_b_snapshot_provenance.sha256}.json" ||
    can(regex("^data-bootstrap/${var.rds_snapshot_source_run_id}/${var.dataset_release}-snapshot/[a-z0-9][a-z0-9-]{2,47}/snapshot-provenance[.]json$", var.global_b_snapshot_provenance.key))) &&
    can(regex("^[0-9a-f]{64}$", var.global_b_snapshot_provenance.sha256)) &&
    can(regex("^[A-Za-z0-9._~+/=-]+$", var.global_b_snapshot_provenance.version_id)) &&
    !contains(["", "null", "None"], var.global_b_snapshot_provenance.version_id) &&
    var.global_b_snapshot_provenance.bytes > 0 && var.global_b_snapshot_provenance.bytes <= 4 * 1024 * 1024 &&
    data.aws_s3_object.growth_b_snapshot_provenance[0].version_id == var.global_b_snapshot_provenance.version_id &&
    sha256(base64decode(nonsensitive(data.aws_s3_object.growth_b_snapshot_provenance[0].body_base64))) == var.global_b_snapshot_provenance.sha256 &&
    local.growth_b_snapshot_provenance.schemaVersion == 1 &&
    local.growth_b_snapshot_provenance.kind == "global-growth-b-rds-snapshot-provenance" &&
    local.growth_b_snapshot_provenance.state == "SNAPSHOT_AVAILABLE_PROVENANCE_VERIFIED" &&
    local.growth_b_snapshot_provenance.sourceDeletionAllowed == false && local.growth_b_snapshot_provenance.actualRestoreVerified == false &&
    local.growth_b_snapshot_provenance.sourceFreeze.heldUntilSnapshotAvailable &&
    local.growth_b_snapshot_provenance.allRowsAndDdlBeforeAndAfterSnapshotEqual &&
    local.growth_b_snapshot_core.kind == "global-growth-b-rds-snapshot-provenance" &&
    local.growth_b_snapshot_core.account == var.account_id && local.growth_b_snapshot_core.region == var.aws_region &&
    local.growth_b_snapshot_core.datasetId == var.dataset_release &&
    local.growth_b_snapshot_core.mysql == { version = "8.4.11", flywayVersion = 28, schema = "airbobdb" } &&
    local.growth_b_snapshot_core.toolIdentity == local.growth_b_snapshot_tool_sources &&
    sha256(jsonencode(local.growth_b_snapshot_core)) == local.growth_b_snapshot_provenance.contractSha256 &&
    sha256(jsonencode(local.growth_b_snapshot_core.preparedFingerprint)) == local.growth_b_snapshot_core.preparedFingerprintCanonicalSha256 &&
    local.growth_b_snapshot_core.application.image == var.app_image_reference &&
    local.growth_b_snapshot_core.application.mainCommit == var.bundle_commit &&
    local.growth_b_snapshot_core.source.identifier == "airbob-${var.rds_snapshot_source_run_id}" &&
    local.growth_b_snapshot_core.source.resourceId == var.rds_snapshot_source_resource_id &&
    local.growth_b_snapshot_core.source.identifier != "airbob-${var.run_id}" &&
    !contains(data.aws_db_instances.growth_b_snapshot_targets[0].instance_identifiers, local.growth_b_snapshot_core.source.identifier) &&
    local.growth_b_snapshot_core.snapshotIdentifier == var.rds_snapshot_identifier &&
    startswith(var.rds_snapshot_identifier, "airbob-dataset-b-") &&
    local.lab_contract.approved_rds_snapshot_identifier == var.rds_snapshot_identifier &&
    local.growth_b_snapshot_provenance.snapshot.identifier == var.rds_snapshot_identifier &&
    local.growth_b_snapshot_provenance.snapshot.state == "available" &&
    local.growth_b_snapshot_provenance.snapshot.sourceRdsResourceId == var.rds_snapshot_source_resource_id &&
    data.aws_db_snapshot.dataset[0].db_snapshot_arn == local.growth_b_snapshot_provenance.snapshot.arn &&
    data.aws_db_snapshot.dataset[0].db_instance_identifier == local.growth_b_snapshot_core.source.identifier &&
    data.aws_db_snapshot.dataset[0].status == "available" && data.aws_db_snapshot.dataset[0].snapshot_type == "manual" &&
    data.aws_db_snapshot.dataset[0].engine == "mysql" && data.aws_db_snapshot.dataset[0].engine_version == "8.4.11" &&
    data.aws_db_snapshot.dataset[0].encrypted && local.growth_b_snapshot_provenance.snapshot.encrypted &&
    data.aws_db_snapshot.dataset[0].kms_key_id == local.growth_b_snapshot_provenance.snapshot.kmsKeyArn &&
    data.aws_db_snapshot.dataset[0].allocated_storage == 100 && local.growth_b_snapshot_provenance.snapshot.allocatedStorageGiB == 100 &&
    data.aws_db_snapshot.dataset[0].iops == 3000 && data.aws_db_snapshot.dataset[0].storage_type == "gp3" && local.growth_b_snapshot_provenance.snapshot.storageType == "gp3" &&
    alltrue([for key, value in local.growth_b_snapshot_provenance.snapshot.tags : data.aws_db_snapshot.dataset[0].tags[key] == value]) &&
    data.aws_db_snapshot.dataset[0].tags.Project == "airbob" && data.aws_db_snapshot.dataset[0].tags.Environment == "performance-lab" &&
    data.aws_db_snapshot.dataset[0].tags.Stack == "dataset" && data.aws_db_snapshot.dataset[0].tags.Persistence == "persistent" &&
    data.aws_db_snapshot.dataset[0].tags.ManagedBy == "global-b-snapshot" && data.aws_db_snapshot.dataset[0].tags.BProvenanceSchemaVersion == "1" &&
    data.aws_db_snapshot.dataset[0].tags.DatasetId == var.dataset_release &&
    data.aws_db_snapshot.dataset[0].tags.SourceRdsResourceId == var.rds_snapshot_source_resource_id &&
    data.aws_db_snapshot.dataset[0].tags.SourceRunId == var.rds_snapshot_source_run_id &&
    data.aws_db_snapshot.dataset[0].tags.SourceMysqlUuid == local.growth_b_snapshot_core.source.serverUuid &&
    data.aws_db_snapshot.dataset[0].tags.MysqlVersion == "8.4.11" && data.aws_db_snapshot.dataset[0].tags.FlywayVersion == "28" &&
    data.aws_db_snapshot.dataset[0].tags.SnapshotContractSha256 == local.growth_b_snapshot_provenance.contractSha256 &&
    data.aws_db_snapshot.dataset[0].tags.AppJarSha256 == local.growth_b_snapshot_core.application.appJarSha256 &&
    data.aws_db_snapshot.dataset[0].tags.PreparedFingerprintSha256 == local.growth_b_snapshot_core.evidence.preparedFingerprintSha256 &&
    data.aws_db_snapshot.dataset[0].tags.DumpSha256 == local.growth_b_snapshot_core.publication.objects["airbob-growth.sql.gz"].sha256,
    false,
  )
}

data "aws_s3_object" "growth_b_snapshot_provenance" {
  download_body = true
  count         = local.services_enabled && local.growth_b_snapshot_selected ? 1 : 0
  bucket        = local.growth_b_snapshot_bucket
  key           = try(var.global_b_snapshot_provenance.key, "invalid-b-snapshot-provenance")
  version_id    = try(var.global_b_snapshot_provenance.version_id, "invalid-b-version")
}

data "aws_db_instances" "growth_b_snapshot_targets" {
  count = local.services_enabled && local.growth_b_snapshot_selected ? 1 : 0
}

output "global_b_snapshot" {
  value = {
    selected                          = local.growth_b_snapshot_selected
    restore_only                      = var.global_b_snapshot_restore_only
    provenance                        = var.global_b_snapshot_provenance
    source_absence_required           = true
    actual_restored_database_verified = false
    normal_service_ready              = false
  }
}
