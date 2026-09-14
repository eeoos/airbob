locals {
  growth_b_helper_names = [
    "growth_b_prepare.py", "growth_b_aws_restore.py", "growth_b_aws_contract.py",
    "growth_b_contract.py", "growth_b_runtime.py",
    "growth_b_inventory.py",
  ]
  growth_b_helper_sources = { for name in local.growth_b_helper_names : name => filesha256("${path.module}/../scripts/${name}") }
  growth_b_envelope = local.services_enabled && var.global_b_prepare_only ? try(
    jsondecode(base64decode(nonsensitive(data.aws_s3_object.growth_b_envelope[0].body_base64))), null,
  ) : null
  growth_b_file_names = merge({
    envelope      = "envelope.json", publicationReceipt = "publication-receipt.json", appJar = "app.jar",
    consumerTools = "consumer-tools.tar.gz", toolchain = "toolchain.tar.gz", toolchainManifest = "toolchain.json",
    rdsCaBundle   = "rds-ca.pem", binlogBasis = "binlog-budget.json",
  }, try(local.dataset_manifest.scope == "final-b-rds", false) ? { smallRdsReceipt = "small-rds-receipt.json" } : {})

  growth_b_dataset_valid = !local.services_enabled || try(
    var.global_b_prepare_only && var.database_bootstrap == "dump" && var.rds_engine_version == "8.4.11" &&
    !var.app_enabled && !var.load_generator_enabled && var.mode == "performance" && var.dns_mode == "direct-only" && !local.data_ready &&
    sha256(local.dataset_manifest_body) == var.dataset_manifest_sha256 &&
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
    (var.global_b_import_from_mac ? (
      toset(keys(local.dataset_manifest.toolSources)) == toset(local.growth_b_helper_names) &&
      alltrue([for digest in values(local.dataset_manifest.toolSources) : can(regex("^[0-9a-f]{64}$", digest))])
    ) : local.dataset_manifest.toolSources == local.growth_b_helper_sources) &&
    toset(keys(local.dataset_manifest.files)) == toset(keys(local.growth_b_file_names)) &&
    alltrue([for name, item in local.dataset_manifest.files :
      toset(keys(item)) == toset(["key", "versionId", "sha256", "bytes"]) &&
      item.key == "datasets/${var.dataset_release}-aws-preparation/files/${item.sha256}-${local.growth_b_file_names[name]}" &&
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
    sha256(base64decode(nonsensitive(data.aws_s3_object.growth_b_envelope[0].body_base64))) == local.dataset_manifest.files.envelope.sha256 &&
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

  growth_b_context = local.services_enabled && var.global_b_prepare_only && !var.global_b_import_from_mac ? {
    runId               = var.run_id
    datasetId           = var.dataset_release
    manifestVersionId   = var.global_b_manifest_version_id
    manifestSha256      = var.dataset_manifest_sha256
    toolSources         = local.growth_b_helper_sources
    rdsInstanceClass    = var.rds_instance_class
    rdsClassGuardSha256 = filesha256("${path.module}/../scripts/growth_b_rds_class.py")
    expiresAt           = var.expires_at
    redisImage          = var.infra_image_references.REDIS_IMAGE
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
      identifier      = module.rds[0].identifier
      resourceId      = module.rds[0].resource_id
      endpoint        = module.rds[0].address
      masterSecretArn = module.rds[0].master_secret_arn
    }
  } : null

  growth_b_bootstrap_command = local.services_enabled && var.global_b_prepare_only && !var.global_b_import_from_mac ? join("\n", [
    "set -euo pipefail", "umask 077",
    "for attempt in $(seq 1 120); do test -f /var/lib/airbob/b-host-ready && break; sleep 5; done",
    "test -f /var/lib/airbob/b-host-ready",
    "install -d -m 700 /opt/airbob/bootstrap-helpers",
    "printf '%s' '${base64gzip(file("${path.module}/../scripts/growth_b_rds_class.py"))}' | base64 --decode | gzip --decompress > /opt/airbob/bootstrap-helpers/growth_b_rds_class.py",
    "printf '%s  %s\\n' '${filesha256("${path.module}/../scripts/growth_b_rds_class.py")}' /opt/airbob/bootstrap-helpers/growth_b_rds_class.py | sha256sum --check --status",
    "printf '%s' '${base64encode(jsonencode(local.growth_b_context))}' | base64 --decode > /opt/airbob/bootstrap-helpers/global-b-context.json",
    "printf '%s' '${base64gzip(file("${path.module}/../scripts/bootstrap-growth-b-entry.sh"))}' | base64 --decode | gzip --decompress > /opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh",
    "printf '%s  %s\\n' '${filesha256("${path.module}/../scripts/bootstrap-growth-b-entry.sh")}' /opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh | sha256sum --check --status",
    "chmod 700 /opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh",
    "/opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh /opt/airbob/bootstrap-helpers/global-b-context.json",
  ]) : ""
  bootstrap_data_command = var.global_b_prepare_only ? local.growth_b_bootstrap_command : (var.global_b_services ? local.growth_b_service_bootstrap_command : local.legacy_bootstrap_data_command)
}

data "aws_s3_object" "growth_b_envelope" {
  download_body = true
  count         = local.services_enabled && var.global_b_prepare_only ? 1 : 0

  bucket     = local.lab_contract.dataset_bucket_name
  key        = try(local.dataset_manifest.files.envelope.key, "invalid-b-envelope")
  version_id = try(local.dataset_manifest.files.envelope.versionId, "invalid-b-version")
}

output "global_b_preparation" {
  description = "B data-only coordinates; a separate verified preparation receipt is required for completion."
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

output "global_b_mac_import" {
  description = "Non-secret coordinates for Mac import through the existing NAT SSM tunnel."
  value = local.services_enabled && var.global_b_prepare_only && var.global_b_import_from_mac ? {
    selected              = true
    rds_instance_id       = module.rds[0].identifier
    rds_resource_id       = module.rds[0].resource_id
    rds_endpoint          = module.rds[0].address
    rds_master_secret_arn = module.rds[0].master_secret_arn
    nat_instance_id       = module.nat.instance_id
  } : null
}

locals {
  growth_b_service_mac_source = try(local.dataset_manifest.preparation.sourceMode == "mac-sql-postcheck", false)
  growth_b_service_helper_names = concat(local.growth_b_helper_names, ["growth_b_service.py", "growth_b_search.py", "growth_b_app_runtime.py"],
  local.growth_b_service_mac_source ? ["growth_b_mac_service.py", "growth_b_mac_downsize.py"] : [])
  growth_b_service_helper_sources = { for name in local.growth_b_service_helper_names : name => filesha256("${path.module}/../scripts/${name}") }
  growth_b_service_prefix         = "datasets/${var.dataset_release}-aws-service/${var.global_b_service_release}"
  growth_b_search_prefix          = try("datasets/${var.dataset_release}-search/${local.dataset_manifest.search.snapshotRelease}", "invalid-b-search")
  growth_b_service_refs = var.global_b_services && local.services_enabled ? try({
    preparation = { bucket = local.lab_contract.evidence_bucket_name, ref = local.dataset_manifest.preparation.receipt }
    ca          = { bucket = local.lab_contract.dataset_bucket_name, ref = local.dataset_manifest.preparation.rdsCaBundle }
    tools       = { bucket = local.lab_contract.dataset_bucket_name, ref = local.dataset_manifest.consumerTools }
    transport   = { bucket = local.lab_contract.dataset_bucket_name, ref = local.dataset_manifest.search.transport }
    appRuntime  = { bucket = local.lab_contract.dataset_bucket_name, ref = local.dataset_manifest.appRuntimeBinding }
  }, {}) : {}
  growth_b_cdc_suffix = try(substr(sha256("${var.run_id}:${local.dataset_manifest.rds.serverUuid}"), 0, 20), "")
  growth_b_service_manifest_valid = !local.services_enabled || try(
    var.global_b_services && !var.global_b_prepare_only && contains(["dump", "snapshot"], var.database_bootstrap) && var.rds_engine_version == "8.4.11" &&
    var.mode == "performance" && var.dns_mode == "direct-only" && !var.load_generator_enabled &&
    sha256(local.dataset_manifest_body) == var.dataset_manifest_sha256 &&
    data.aws_s3_object.dataset_manifest[0].version_id == var.global_b_manifest_version_id &&
    toset(keys(local.dataset_manifest)) == toset(["schemaVersion", "kind", "datasetId", "runId", "serviceRelease", "account", "region",
    "mysql", "rds", "application", "appRuntimeBinding", "preparation", "search", "debezium", "consumerTools", "toolSources", "cdc"]) &&
    local.dataset_manifest.schemaVersion == 1 && local.dataset_manifest.kind == "global-growth-b-aws-service" &&
    local.dataset_manifest.datasetId == var.dataset_release && local.dataset_manifest.runId == var.run_id &&
    local.dataset_manifest.serviceRelease == var.global_b_service_release &&
    local.dataset_manifest.account == var.account_id && local.dataset_manifest.region == var.aws_region &&
    local.dataset_manifest.mysql == { version = "8.4.11", flywayVersion = 28, schema = "airbobdb" } &&
    toset(keys(local.dataset_manifest.rds)) == toset(["identifier", "resourceId", "serverUuid"]) &&
    local.dataset_manifest.rds.identifier == "airbob-${var.run_id}" &&
    can(regex("^db-[A-Z0-9]+$", local.dataset_manifest.rds.resourceId)) &&
    can(regex("^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$", local.dataset_manifest.rds.serverUuid)) &&
    local.dataset_manifest.application.image == var.app_image_reference &&
    local.dataset_manifest.application.mainCommit == var.bundle_commit &&
    toset(keys(local.dataset_manifest.application)) == toset(["mainCommit", "image", "appJarSha256", "migrationFilesSha256"]) &&
    alltrue([for key in ["appJarSha256", "migrationFilesSha256"] : can(regex("^[0-9a-f]{64}$", local.dataset_manifest.application[key]))]) &&
    local.dataset_manifest.appRuntimeBinding.key == "${local.growth_b_service_prefix}/files/app-runtime-binding.json" &&
    sha256(base64decode(nonsensitive(data.aws_s3_object.growth_b_service_runtime[0].body_base64))) == local.dataset_manifest.appRuntimeBinding.sha256 &&
    data.aws_s3_object.growth_b_service_runtime[0].version_id == local.dataset_manifest.appRuntimeBinding.versionId &&
    local.growth_b_service_runtime.kind == "global-b-app-runtime-binding" && local.growth_b_service_runtime.schemaVersion == 1 &&
    toset(keys(local.growth_b_service_runtime.runtime)) == toset(["imageJarSha256", "runtimeDigest", "runtimeContract", "runtimeRevision", "sourceJarSha256", "image", "mainCommit"]) &&
    local.growth_b_service_runtime.runtime.runtimeContract == "global-b-main-479-entry-bytes-v1" &&
    local.growth_b_service_runtime.runtime.runtimeRevision == local.dataset_manifest.application.mainCommit &&
    local.growth_b_service_runtime.runtime.mainCommit == local.dataset_manifest.application.mainCommit &&
    local.growth_b_service_runtime.runtime.image == local.dataset_manifest.application.image &&
    local.growth_b_service_runtime.runtime.sourceJarSha256 == local.dataset_manifest.application.appJarSha256 &&
    alltrue([for key in ["imageJarSha256", "runtimeDigest"] : can(regex("^[0-9a-f]{64}$", local.growth_b_service_runtime.runtime[key]))]) &&
    toset(keys(local.dataset_manifest.debezium)) == toset(["image", "buildCommit", "pluginVersion", "pluginJarSha256", "connectVersion"]) &&
    local.dataset_manifest.debezium.image == var.infra_image_references.DEBEZIUM_IMAGE &&
    local.dataset_manifest.debezium.pluginVersion == "3.0.8.Final" &&
    can(regex("^[0-9a-f]{40}$", local.dataset_manifest.debezium.buildCommit)) &&
    local.dataset_manifest.debezium.pluginJarSha256 == "6de35d7c20ca1d00e6d9d8ae0e033203e487bf29e335a096dcaf38d4e0316f59" &&
    local.dataset_manifest.debezium.connectVersion == "3.7.0" &&
    local.dataset_manifest.toolSources == local.growth_b_service_helper_sources &&
    local.dataset_manifest.consumerTools.key == "${local.growth_b_service_prefix}/files/consumer-tools.tar.gz" &&
    local.dataset_manifest.preparation.rdsCaBundle.key == "datasets/${var.dataset_release}-aws-preparation/files/${local.dataset_manifest.preparation.rdsCaBundle.sha256}-rds-ca.pem" &&
    startswith(local.dataset_manifest.preparation.receipt.key, "data-bootstrap/${var.run_id}/") &&
    (local.growth_b_service_mac_source ? (
      local.dataset_manifest.preparation.receipt.key == "data-bootstrap/${var.run_id}/${var.dataset_release}-mac-rds-downsize.json" &&
      alltrue([for name in ["sqlImportReceipt", "postcheckReceipt"] :
        startswith(local.dataset_manifest.preparation[name].key, "data-bootstrap/${var.run_id}/") &&
        can(regex("^[0-9a-f]{64}$", local.dataset_manifest.preparation[name].sha256)) &&
        !contains(["", "null", "None"], local.dataset_manifest.preparation[name].versionId)
      ])
    ) : alltrue([for key in ["restoreConfigSha256", "restoreReceiptSha256", "preparedFingerprintSha256"] : can(regex("^[0-9a-f]{64}$", local.dataset_manifest.preparation[key]))])) &&
    local.dataset_manifest.search.image == var.infra_image_references.ELASTICSEARCH_IMAGE &&
    can(regex("^${var.dataset_release}-search-[a-z0-9][a-z0-9._-]{0,60}$", local.dataset_manifest.search.snapshotRelease)) &&
    local.dataset_manifest.search.transport.key == "${local.growth_b_search_prefix}/transport-manifest.json" &&
    (local.growth_b_service_mac_source ? local.dataset_manifest.search.restoreReceipt == null : (!var.global_b_service_bootstrap_enabled || (
      startswith(local.dataset_manifest.search.restoreReceipt.key, "data-bootstrap/${var.run_id}/") &&
      can(regex("^[0-9a-f]{64}$", local.dataset_manifest.search.restoreReceipt.sha256)) &&
      !contains(["", "null", "None"], local.dataset_manifest.search.restoreReceipt.versionId)
    ))) &&
    alltrue([for item in local.growth_b_service_refs :
      toset(keys(item.ref)) == toset(["key", "versionId", "sha256", "bytes"]) &&
      can(regex("^[A-Za-z0-9_./-]+$", item.ref.key)) && !strcontains(item.ref.key, "/../") && !strcontains(item.ref.key, "/./") &&
      can(regex("^[A-Za-z0-9._~+/=-]+$", item.ref.versionId)) && !contains(["", "null", "None"], item.ref.versionId) &&
      can(regex("^[0-9a-f]{64}$", item.ref.sha256)) && item.ref.bytes > 0 && item.ref.bytes <= 20 * 1024 * 1024 && floor(item.ref.bytes) == item.ref.bytes
    ]) &&
    sha256(base64decode(nonsensitive(data.aws_s3_object.growth_b_service_preparation[0].body_base64))) == local.dataset_manifest.preparation.receipt.sha256 &&
    (local.growth_b_service_mac_source ? (
      var.database_bootstrap == "dump" && var.rds_instance_class == "db.t3.small" &&
      local.growth_b_service_preparation.kind == "global-b-mac-rds-downsize" &&
      local.growth_b_service_preparation.state == "SAME_RDS_DOWNSIZED_AND_TERRAFORM_ALIGNED" &&
      local.growth_b_service_preparation.sourceSha256 == local.growth_b_service_helper_sources["growth_b_mac_downsize.py"] &&
      local.growth_b_service_preparation.operator.globalBPrepareOnly && local.growth_b_service_preparation.operator.globalBImportFromMac &&
      local.growth_b_service_preparation.operator.runId == var.run_id &&
      local.growth_b_service_preparation.operator.datasetRelease == var.dataset_release &&
      local.growth_b_service_preparation.operator.fencingToken == var.fencing_token &&
      local.growth_b_service_preparation.operator.expiresAt == var.expires_at &&
      local.growth_b_service_preparation.operator.bundleCommit == var.bundle_commit &&
      local.growth_b_service_preparation.operator.appImageReference == var.app_image_reference &&
      local.growth_b_service_preparation.rds.identifier == local.dataset_manifest.rds.identifier &&
      local.growth_b_service_preparation.rds.resourceId == local.dataset_manifest.rds.resourceId &&
      local.growth_b_service_preparation.rds.serverUuid == local.dataset_manifest.rds.serverUuid &&
      local.growth_b_service_preparation.fromClass == "db.m6i.large" && local.growth_b_service_preparation.toClass == "db.t3.small" &&
      local.growth_b_service_preparation.sqlImportSha256 == local.dataset_manifest.preparation.sqlImportReceipt.sha256 &&
      local.growth_b_service_preparation.postcheckSha256 == local.dataset_manifest.preparation.postcheckReceipt.sha256 &&
      !local.growth_b_service_preparation.sqlReplayed && !local.growth_b_service_preparation.fullDatasetValidated &&
      !local.growth_b_service_preparation.servicesStarted
      ) : (
      local.growth_b_service_preparation.kind == "global-growth-b-aws-data-only-preparation" &&
      local.growth_b_service_preparation.state == "DATABASE_INVENTORY_LOGIN_VERIFIED" &&
      local.growth_b_service_preparation.runId == var.run_id && local.growth_b_service_preparation.datasetId == var.dataset_release &&
      local.growth_b_service_preparation.rdsResourceId == local.dataset_manifest.rds.resourceId &&
      local.growth_b_service_preparation.serverUuid == local.dataset_manifest.rds.serverUuid &&
      local.growth_b_service_preparation.restoreReceiptSha256 == local.dataset_manifest.preparation.restoreReceiptSha256 &&
      local.growth_b_service_preparation.preparation.preparedFingerprintSha256 == local.dataset_manifest.preparation.preparedFingerprintSha256 &&
      !local.growth_b_service_preparation.deploymentReady && !local.growth_b_service_preparation.applicationLeftRunning &&
      (var.database_bootstrap == "snapshot" ? (
        local.growth_b_service_preparation.sourceMode == "verified-global-b-snapshot" &&
        local.growth_b_service_preparation.snapshotProvenanceSha256 == var.global_b_snapshot_provenance.sha256 &&
        local.growth_b_service_preparation.snapshotRestoreEvidence.evidenceSource == "controller-pinned-cloudtrail-event" &&
        can(regex("^[0-9a-f]{64}$", local.growth_b_service_preparation.snapshotRestoreEvidence.eventSha256))
        ) : (!contains(keys(local.growth_b_service_preparation), "snapshotProvenanceSha256") &&
      contains(["dump", ""], try(coalesce(local.growth_b_service_preparation.sourceMode, ""), ""))))
    )) &&
    sha256(base64decode(nonsensitive(data.aws_s3_object.growth_b_service_transport[0].body_base64))) == local.dataset_manifest.search.transport.sha256 &&
    local.growth_b_service_transport.schemaVersion == 1 && local.growth_b_service_transport.kind == "global-growth-b-search-transport" &&
    local.growth_b_service_transport.bucket == local.lab_contract.dataset_bucket_name && local.growth_b_service_transport.region == var.aws_region &&
    local.growth_b_service_transport.datasetId == var.dataset_release &&
    local.growth_b_service_transport.snapshotRelease == local.dataset_manifest.search.snapshotRelease &&
    local.growth_b_service_transport.source.appJarSha256 == local.dataset_manifest.application.appJarSha256 &&
    local.growth_b_service_transport.repository == { type = "s3", bucket = local.lab_contract.dataset_bucket_name, basePath = "${local.growth_b_search_prefix}/native" } &&
    local.dataset_manifest.cdc == {
      connectorName      = "airbob-b-${local.growth_b_cdc_suffix}"
      topicPrefix        = "airbob_b_${local.growth_b_cdc_suffix}"
      schemaHistoryTopic = "schemahistory.airbob_b_${local.growth_b_cdc_suffix}"
      databaseServerId   = 100000 + parseint(substr(local.growth_b_cdc_suffix, 0, 7), 16)
      username           = "b_cdc_${local.growth_b_cdc_suffix}"
    }, false,
  )
  growth_b_service_preparation = var.global_b_services && local.services_enabled ? try(jsondecode(base64decode(nonsensitive(data.aws_s3_object.growth_b_service_preparation[0].body_base64))), null) : null
  growth_b_service_transport   = var.global_b_services && local.services_enabled ? try(jsondecode(base64decode(nonsensitive(data.aws_s3_object.growth_b_service_transport[0].body_base64))), null) : null
  growth_b_service_runtime     = var.global_b_services && local.services_enabled ? try(jsondecode(base64decode(nonsensitive(data.aws_s3_object.growth_b_service_runtime[0].body_base64))), null) : null
  growth_b_readiness_valid = !local.data_ready || try(
    var.global_b_services && var.global_b_readiness_receipt != null &&
    var.global_b_readiness_receipt.key == "data-bootstrap/${var.run_id}/${var.dataset_release}-service-${var.global_b_service_release}.json" &&
    data.aws_s3_object.data_bootstrap_receipt[0].version_id == var.global_b_readiness_receipt.version_id &&
    sha256(base64decode(nonsensitive(data.aws_s3_object.data_bootstrap_receipt[0].body_base64))) == var.global_b_readiness_receipt.sha256 &&
    local.data_bootstrap_receipt.schemaVersion == 1 && local.data_bootstrap_receipt.kind == "global-growth-b-aws-service-readiness" &&
    local.data_bootstrap_receipt.state == "GLOBAL_B_DEPENDENCIES_VERIFIED" &&
    local.data_bootstrap_receipt.runId == var.run_id && local.data_bootstrap_receipt.datasetId == var.dataset_release &&
    local.data_bootstrap_receipt.serviceRelease == var.global_b_service_release &&
    local.data_bootstrap_receipt.manifestSha256 == var.dataset_manifest_sha256 &&
    local.data_bootstrap_receipt.rds == local.dataset_manifest.rds &&
    local.data_bootstrap_receipt.rds.resourceId == module.rds[0].resource_id &&
    local.data_bootstrap_receipt.mysql == local.dataset_manifest.mysql &&
    local.data_bootstrap_receipt.application == local.dataset_manifest.application &&
    local.data_bootstrap_receipt.appRuntimeBinding == local.dataset_manifest.appRuntimeBinding &&
    local.data_bootstrap_receipt.appRuntime == local.growth_b_service_runtime.runtime &&
    local.data_bootstrap_receipt.debezium == local.dataset_manifest.debezium && local.data_bootstrap_receipt.debeziumVerified &&
    local.data_bootstrap_receipt.preparationReceipt == local.dataset_manifest.preparation.receipt &&
    (local.growth_b_service_mac_source ? (
      local.data_bootstrap_receipt.sourceMode == "mac-sql-postcheck" &&
      local.data_bootstrap_receipt.sqlImportReceipt == local.dataset_manifest.preparation.sqlImportReceipt &&
      local.data_bootstrap_receipt.postcheckReceipt == local.dataset_manifest.preparation.postcheckReceipt &&
      !local.data_bootstrap_receipt.fullDatasetValidated && !local.data_bootstrap_receipt.sqlReplayed &&
      local.data_bootstrap_receipt.searchDatasetRestored &&
      local.data_bootstrap_receipt.searchTransport == local.dataset_manifest.search.transport &&
      local.data_bootstrap_receipt.nativeSearch.state == "NATIVE_SEARCH_COUNT_AND_SAMPLE_VERIFIED" &&
      local.data_bootstrap_receipt.nativeSearch.documents == local.dataset_manifest.search.documentFingerprint.documents &&
      local.data_bootstrap_receipt.nativeSearch.transport == local.dataset_manifest.search.transport &&
      local.data_bootstrap_receipt.nativeSearch.restoredIndex == local.data_bootstrap_receipt.restoredIndex &&
      local.data_bootstrap_receipt.nativeSearch.nativeRestoreSucceeded && local.data_bootstrap_receipt.nativeSearch.singleWriteAlias &&
      local.data_bootstrap_receipt.nativeSearch.representativeSearchPassed && local.data_bootstrap_receipt.nativeSearch.repositoryReadOnly &&
      local.data_bootstrap_receipt.nativeSearch.repositoryRemoved && !local.data_bootstrap_receipt.nativeSearch.fullDatasetValidated &&
      !local.data_bootstrap_receipt.nativeSearch.allDocumentSourceFieldsEqual
      ) : (
      local.data_bootstrap_receipt.preparedFingerprintSha256 == local.dataset_manifest.preparation.preparedFingerprintSha256 &&
      local.data_bootstrap_receipt.searchRestoreReceipt == local.dataset_manifest.search.restoreReceipt &&
      local.data_bootstrap_receipt.searchTransport == local.dataset_manifest.search.transport &&
      local.data_bootstrap_receipt.searchFingerprint == local.dataset_manifest.search.documentFingerprint
    )) &&
    local.data_bootstrap_receipt.cdc == local.dataset_manifest.cdc && local.data_bootstrap_receipt.cdcRunning && local.data_bootstrap_receipt.heartbeatObserved &&
    toset(local.data_bootstrap_receipt.topics) == local.dataset_kafka_topics && local.data_bootstrap_receipt.redisSeparate &&
    !local.data_bootstrap_receipt.redisReset && local.data_bootstrap_receipt.writersStopped &&
    !local.data_bootstrap_receipt.applicationStarted && !local.data_bootstrap_receipt.deploymentReady &&
    local.data_bootstrap_receipt.toolSources == local.growth_b_service_helper_sources,
    false,
  )

  growth_b_service_context = var.global_b_services && local.services_enabled ? {
    runId                    = var.run_id, datasetId = var.dataset_release, serviceRelease = var.global_b_service_release
    manifestVersionId        = var.global_b_manifest_version_id, manifestSha256 = var.dataset_manifest_sha256
    toolSources              = local.growth_b_service_helper_sources
    databaseBootstrap        = var.database_bootstrap
    macSource                = local.growth_b_service_mac_source
    redisImage               = var.infra_image_references.REDIS_IMAGE
    rdsInstanceClass         = var.rds_instance_class
    rdsClassGuardSha256      = filesha256("${path.module}/../scripts/growth_b_rds_class.py")
    resourceFence            = var.fencing_token
    expiresAt                = var.expires_at
    snapshotProvenanceSha256 = var.database_bootstrap == "snapshot" ? var.global_b_snapshot_provenance.sha256 : null
    lease = { table = local.lab_contract.lease_table_name, lockName = local.lab_contract.lease_lock_id,
    owner = var.global_b_lease_owner, runId = var.run_id, command = "up", fencingToken = var.global_b_lease_fencing_token }
    rds               = { identifier = module.rds[0].identifier, resourceId = module.rds[0].resource_id, endpoint = module.rds[0].address, masterSecretArn = module.rds[0].master_secret_arn }
    debeziumSecretArn = aws_secretsmanager_secret.debezium[0].arn
  } : null
  growth_b_service_bootstrap_command = var.global_b_services && local.services_enabled ? join("\n", [
    "set -euo pipefail", "umask 077", "install -d -m 700 /opt/airbob/bootstrap-helpers",
    "printf '%s' '${base64gzip(file("${path.module}/../scripts/growth_b_rds_class.py"))}' | base64 --decode | gzip --decompress > /opt/airbob/bootstrap-helpers/growth_b_rds_class.py",
    "printf '%s  %s\\n' '${filesha256("${path.module}/../scripts/growth_b_rds_class.py")}' /opt/airbob/bootstrap-helpers/growth_b_rds_class.py | sha256sum --check --status",
    "printf '%s' '${base64encode(jsonencode(local.growth_b_service_context))}' | base64 --decode > /opt/airbob/bootstrap-helpers/global-b-service-context.json",
    "printf '%s' '${base64gzip(file("${path.module}/../scripts/bootstrap-growth-b-services.sh"))}' | base64 --decode | gzip --decompress > /opt/airbob/bootstrap-helpers/bootstrap-growth-b-services.sh",
    "printf '%s  %s\\n' '${filesha256("${path.module}/../scripts/bootstrap-growth-b-services.sh")}' /opt/airbob/bootstrap-helpers/bootstrap-growth-b-services.sh | sha256sum --check --status",
    "bash /opt/airbob/bootstrap-helpers/bootstrap-growth-b-services.sh /opt/airbob/bootstrap-helpers/global-b-service-context.json",
  ]) : ""

  # The preparation host's user_data is stable across this transition. SSM adds
  # the public Connect bundle without replacing its retained volume or database.
  growth_b_connect_bundle = var.global_b_services && local.services_enabled ? templatefile("${path.module}/templates/host-user-data.sh.tftpl", {
    mode                  = "service", service = "debezium", region = var.aws_region, account_id = var.account_id
    bundle_bucket         = local.lab_contract.bundle_bucket_name, bundle_archive_key = local.bundle_archive_key
    bundle_checksum_key   = local.bundle_checksum_key, bundle_manifest_key = local.bundle_manifest_key
    bundle_commit         = var.bundle_commit, bundle_sha256 = var.bundle_sha256, bundle_files_json = jsonencode(local.service_bundle_files)
    images_env            = local.phase2_images_env, docker_compose_version = local.docker_compose_version
    docker_compose_sha256 = local.docker_compose_sha256, runtime_contract = ""
  }) : ""
}

resource "aws_vpc_security_group_ingress_rule" "growth_b_cdc_application" {
  count = local.application_infrastructure_enabled && var.global_b_services && var.app_enabled ? 1 : 0

  security_group_id            = module.security.security_group_ids.app
  referenced_security_group_id = module.security.security_group_ids.debezium
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  description                  = "B service verification from the retained Connect host"
  tags                         = local.ephemeral_tags
}

data "aws_s3_object" "growth_b_service_preparation" {
  download_body = true
  count         = var.global_b_services && local.services_enabled ? 1 : 0
  bucket        = local.lab_contract.evidence_bucket_name
  key           = try(local.dataset_manifest.preparation.receipt.key, "invalid-b-preparation")
  version_id    = try(local.dataset_manifest.preparation.receipt.versionId, "invalid-b-version")
}

data "aws_s3_object" "growth_b_service_transport" {
  download_body = true
  count         = var.global_b_services && local.services_enabled ? 1 : 0
  bucket        = local.lab_contract.dataset_bucket_name
  key           = try(local.dataset_manifest.search.transport.key, "invalid-b-transport")
  version_id    = try(local.dataset_manifest.search.transport.versionId, "invalid-b-version")
}

data "aws_s3_object" "growth_b_service_runtime" {
  download_body = true
  count         = var.global_b_services && local.services_enabled ? 1 : 0
  bucket        = local.lab_contract.dataset_bucket_name
  key           = try(local.dataset_manifest.appRuntimeBinding.key, "invalid-b-runtime")
  version_id    = try(local.dataset_manifest.appRuntimeBinding.versionId, "invalid-b-version")
}

resource "aws_ssm_document" "growth_b_connect_bundle" {
  count           = var.global_b_services && local.services_enabled ? 1 : 0
  name            = "airbob-${var.run_id}-b-connect-bundle"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({ schemaVersion = "2.2", description = "Install Connect bundle on retained B preparation host", mainSteps = [{
    action = "aws:runShellScript", name = "installRetainedConnectBundle", inputs = { timeoutSeconds = "2400", runCommand = [local.growth_b_connect_bundle] }
  }] })
  tags = merge(local.ephemeral_tags, { Service = "debezium" })
}

resource "aws_ssm_association" "growth_b_connect_bundle" {
  count                            = var.global_b_services && local.services_enabled ? 1 : 0
  name                             = aws_ssm_document.growth_b_connect_bundle[0].name
  association_name                 = "airbob-${var.run_id}-b-connect-bundle"
  wait_for_success_timeout_seconds = 2700
  targets {
    key    = "InstanceIds"
    values = [module.service_hosts.instance_ids.debezium]
  }
  tags       = merge(local.ephemeral_tags, { Service = "debezium" })
  depends_on = [terraform_data.dataset_release_gate]
}

output "global_b_service" {
  description = "B service admission coordinates; app health and business-cycle receipts remain separate observations."
  value = {
    selected                  = var.global_b_services
    manifest_key              = var.global_b_services ? local.dataset_manifest_key : null
    manifest_version_id       = var.global_b_services ? var.global_b_manifest_version_id : null
    manifest_sha256           = var.global_b_services ? var.dataset_manifest_sha256 : null
    readiness_receipt         = var.global_b_services ? var.global_b_readiness_receipt : null
    readiness_key             = var.global_b_services ? "data-bootstrap/${var.run_id}/${var.dataset_release}-service-${var.global_b_service_release}.json" : null
    application_profile       = var.global_b_services ? "aws" : null
    flyway_target             = var.global_b_services ? 28 : null
    runtime_revision          = var.global_b_services ? local.app_runtime_revision : null
    normal_service_verified   = false
    snapshot_restore_admitted = local.growth_b_snapshot_selected && local.growth_b_snapshot_valid
  }
}

# The actual S3-native restore runs on the ES host so image/container identity is
# observed locally. Its source reader connects to the selected RDS over TLS.
resource "aws_vpc_security_group_ingress_rule" "growth_b_search_rds" {
  count                        = var.global_b_services && local.services_enabled ? 1 : 0
  security_group_id            = module.security.security_group_ids.rds
  referenced_security_group_id = module.security.security_group_ids.elasticsearch
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  description                  = "B native search restore full source verification"
  tags                         = merge(local.ephemeral_tags, { Service = "rds" })
}
