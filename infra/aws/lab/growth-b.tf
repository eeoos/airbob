locals {
  growth_b_helper_names = [
    "growth_b_prepare.py", "growth_b_aws_restore.py", "growth_b_aws_contract.py",
    "growth_b_contract.py", "growth_b_runtime.py",
  ]
  growth_b_helper_sources = { for name in local.growth_b_helper_names : name => filesha256("${path.module}/../scripts/${name}") }
  growth_b_envelope = local.services_enabled && var.global_b_prepare_only ? try(
    jsondecode(nonsensitive(data.aws_s3_object.growth_b_envelope[0].body)), null,
  ) : null
  growth_b_file_names = merge({
    envelope      = "envelope.json", publicationReceipt = "publication-receipt.json", appJar = "app.jar",
    consumerTools = "consumer-tools.tar.gz", toolchain = "toolchain.tar.gz", toolchainManifest = "toolchain.json",
    rdsCaBundle   = "rds-ca.pem", binlogBasis = "binlog-budget.json",
  }, try(local.dataset_manifest.scope == "final-b-rds", false) ? { smallRdsReceipt = "small-rds-receipt.json" } : {})

  growth_b_dataset_valid = !local.services_enabled || try(
    var.global_b_prepare_only && var.database_bootstrap == "dump" && var.rds_engine_version == "8.4.11" &&
    !var.app_enabled && !var.load_generator_enabled && var.mode == "performance" && var.dns_mode == "direct-only" && !local.data_ready &&
    sha256(nonsensitive(data.aws_s3_object.dataset_manifest[0].body)) == var.dataset_manifest_sha256 &&
    data.aws_s3_object.dataset_manifest[0].version_id == var.global_b_manifest_version_id &&
    toset(keys(local.dataset_manifest)) == toset([
      "schemaVersion", "kind", "datasetId", "account", "region", "scope", "mysql", "files",
      "toolSources", "toolchain", "storage", "binlogAdditionalReserveBytes",
    ]) &&
    local.dataset_manifest.schemaVersion == 1 && local.dataset_manifest.kind == "global-growth-b-aws-data-only-preparation" &&
    local.dataset_manifest.datasetId == var.dataset_release && can(regex("^global-growth-b-[0-9a-f]{16}$", var.dataset_release)) &&
    local.dataset_manifest.account == var.account_id && local.dataset_manifest.region == var.aws_region &&
    contains(["small-rds-rehearsal", "final-b-rds"], local.dataset_manifest.scope) &&
    local.dataset_manifest.mysql == { version = "8.4.11", flywayVersion = 28 } &&
    local.dataset_manifest.toolSources == local.growth_b_helper_sources &&
    toset(keys(local.dataset_manifest.files)) == toset(keys(local.growth_b_file_names)) &&
    alltrue([for name, item in local.dataset_manifest.files :
      toset(keys(item)) == toset(["key", "versionId", "sha256", "bytes"]) &&
      item.key == "datasets/${var.dataset_release}/aws-preparation/${local.growth_b_file_names[name]}" &&
      can(regex("^[A-Za-z0-9._~+/=-]+$", item.versionId)) && !contains(["", "null", "None"], item.versionId) && length(item.versionId) <= 1024 &&
      can(regex("^[0-9a-f]{64}$", item.sha256)) && item.bytes > 0 && floor(item.bytes) == item.bytes && item.bytes <= 20 * 1024 * 1024 * 1024
    ]) &&
    local.dataset_manifest.toolchain.system == "Linux" && local.dataset_manifest.toolchain.architecture == "x86_64" &&
    can(regex("^3\\.(1[2-9]|[2-9][0-9])\\.[0-9]+$", local.dataset_manifest.toolchain.pythonVersion)) &&
    can(regex("^21\\.0\\.(1[2-9]|[2-9][0-9])(\\.[0-9]+)?$", local.dataset_manifest.toolchain.javaVersion)) &&
    local.dataset_manifest.toolchain.mysqlVersion == "8.4.11" && local.dataset_manifest.toolchain.awsCliVersion == "2.34.64" &&
    local.dataset_manifest.toolchain.unpackedBytes > 0 && local.dataset_manifest.toolchain.unpackedBytes <= 4 * 1024 * 1024 * 1024 &&
    local.dataset_manifest.storage.rdsAllocatedGiB == 100 && local.dataset_manifest.storage.dataHostRootGiB == 20 &&
    local.dataset_manifest.storage.minimumStagingFreeBytes >= 4 * 1024 * 1024 * 1024 &&
    local.dataset_manifest.storage.minimumStagingFreeBytes < 20 * 1024 * 1024 * 1024 &&
    local.dataset_manifest.binlogAdditionalReserveBytes > 0 &&
    sha256(nonsensitive(data.aws_s3_object.growth_b_envelope[0].body)) == local.dataset_manifest.files.envelope.sha256 &&
    local.growth_b_envelope.kind == "global-growth-b-aws-restore" && local.growth_b_envelope.datasetId == var.dataset_release &&
    local.growth_b_envelope.mysql == { version = "8.4.11", flywayVersion = 28, schema = "airbobdb" } &&
    local.growth_b_envelope.account == var.account_id && local.growth_b_envelope.region == var.aws_region &&
    local.growth_b_envelope.bucket == local.lab_contract.dataset_bucket_name &&
    local.growth_b_envelope.appJarSha256 == local.dataset_manifest.files.appJar.sha256 &&
    local.growth_b_envelope.publicationReceiptSha256 == local.dataset_manifest.files.publicationReceipt.sha256 &&
    (local.dataset_manifest.scope == "final-b-rds" ? (
      local.growth_b_envelope.finalScaleSelected && local.growth_b_envelope.awsExecutionAllowed
      ) : !local.growth_b_envelope.finalScaleSelected && !local.growth_b_envelope.awsExecutionAllowed && local.growth_b_envelope.smallRehearsalEligible
    ) &&
    local.growth_b_envelope.storage.requiredRdsFreeAfterRemovalBytes + local.dataset_manifest.binlogAdditionalReserveBytes <= 100 * 1024 * 1024 * 1024 &&
    sum([for item in local.growth_b_envelope.objects : item.bytes]) + sum([for item in local.dataset_manifest.files : item.bytes]) +
    local.dataset_manifest.toolchain.unpackedBytes + local.growth_b_envelope.storage.requiredAdditionalDataHostFreeBytes +
    2 * local.growth_b_envelope.storage.runtimeExtractionBytes <= local.dataset_manifest.storage.minimumStagingFreeBytes,
    false,
  )

  growth_b_context = local.services_enabled && var.global_b_prepare_only ? {
    runId             = var.run_id
    datasetId         = var.dataset_release
    manifestVersionId = var.global_b_manifest_version_id
    manifestSha256    = var.dataset_manifest_sha256
    toolSources       = local.growth_b_helper_sources
    redisImage        = var.infra_image_references.REDIS_IMAGE
    awsCli = {
      version       = regex("AIRBOB_AWS_CLI_VERSION=([0-9.]+)", file("${path.module}/../toolchain.env"))[0]
      archiveSha256 = regex("AIRBOB_AWS_CLI_LINUX_X86_64_SHA256=([0-9a-f]+)", file("${path.module}/../toolchain.env"))[0]
    }
    lease = {
      table        = local.lab_contract.lease_table_name
      lockName     = local.lab_contract.lease_lock_id
      owner        = var.global_b_lease_owner
      runId        = var.run_id
      command      = "up"
      fencingToken = var.fencing_token
    }
    rds = {
      identifier      = module.rds[0].id
      resourceId      = module.rds[0].resource_id
      endpoint        = module.rds[0].address
      masterSecretArn = module.rds[0].master_secret_arn
    }
  } : null

  growth_b_bootstrap_command = local.services_enabled && var.global_b_prepare_only ? join("\n", [
    "set -euo pipefail", "umask 077",
    "for attempt in $(seq 1 120); do test -f /var/lib/airbob/b-host-ready && break; sleep 5; done",
    "test -f /var/lib/airbob/b-host-ready",
    "install -d -m 700 /opt/airbob/bootstrap-helpers",
    "printf '%s' '${base64encode(jsonencode(local.growth_b_context))}' | base64 --decode > /opt/airbob/bootstrap-helpers/global-b-context.json",
    "printf '%s' '${base64gzip(file("${path.module}/../scripts/bootstrap-growth-b-entry.sh"))}' | base64 --decode | gzip --decompress > /opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh",
    "printf '%s  %s\\n' '${filesha256("${path.module}/../scripts/bootstrap-growth-b-entry.sh")}' /opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh | sha256sum --check --status",
    "chmod 700 /opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh",
    "/opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh /opt/airbob/bootstrap-helpers/global-b-context.json",
  ]) : ""
  bootstrap_data_command = var.global_b_prepare_only ? local.growth_b_bootstrap_command : local.legacy_bootstrap_data_command
}

data "aws_s3_object" "growth_b_envelope" {
  count = local.services_enabled && var.global_b_prepare_only ? 1 : 0

  bucket     = local.lab_contract.dataset_bucket_name
  key        = try(local.dataset_manifest.files.envelope.key, "invalid-b-envelope")
  version_id = try(local.dataset_manifest.files.envelope.versionId, "invalid-b-version")
}

output "global_b_preparation" {
  description = "B data-only coordinates; a separate verified host receipt is required for completion."
  value = {
    selected                    = var.global_b_prepare_only
    dataset_id                  = var.dataset_release
    scope                       = try(local.dataset_manifest.scope, null)
    host_instance_id            = var.global_b_prepare_only ? try(module.service_hosts.instance_ids.debezium, null) : null
    receipt_key                 = var.global_b_prepare_only ? "data-bootstrap/${var.run_id}/${var.dataset_release}.json" : null
    expected_manifest_sha256    = var.dataset_manifest_sha256
    app_asg_created             = var.global_b_prepare_only ? false : null
    alb_created                 = var.global_b_prepare_only ? false : null
    cdc_started                 = var.global_b_prepare_only ? false : null
    deployment_ready            = false
    completion_requires_receipt = true
  }
}
