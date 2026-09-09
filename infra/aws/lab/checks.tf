locals {
  network_receipt = try(
    jsondecode(nonsensitive(data.aws_s3_object.network_receipt[0].body)),
    null,
  )
  network_receipt_valid = !local.receipt_required || try(
    toset(keys(local.network_receipt)) == toset([
      "schemaVersion",
      "runId",
      "vpcId",
      "primaryRouteTableId",
      "probeInstanceId",
      "amiId",
      "s3Gateway",
      "ecrApi",
      "ssmApi",
      "secretsManagerApi",
      "verifiedAt",
    ]) &&
    local.network_receipt.schemaVersion == 1 &&
    local.network_receipt.runId == var.run_id &&
    local.network_receipt.vpcId == module.network.vpc_id &&
    local.network_receipt.primaryRouteTableId == module.network.private_route_table_ids.primary &&
    local.network_receipt.probeInstanceId == var.verified_probe_instance_id &&
    local.network_receipt.amiId == var.ami_id &&
    local.network_receipt.s3Gateway == "verified" &&
    local.network_receipt.ecrApi == "verified" &&
    local.network_receipt.ssmApi == "verified" &&
    local.network_receipt.secretsManagerApi == "verified" &&
    can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", local.network_receipt.verifiedAt)),
    false,
  )

  probe_clearance_receipt = try(
    jsondecode(nonsensitive(data.aws_s3_object.probe_clearance_receipt[0].body)),
    null,
  )
  probe_clearance_valid = !local.services_enabled || try(
    toset(keys(local.probe_clearance_receipt)) == toset([
      "schemaVersion",
      "runId",
      "vpcId",
      "probeInstanceId",
      "instanceState",
      "clearedAt",
    ]) &&
    local.probe_clearance_receipt.schemaVersion == 1 &&
    local.probe_clearance_receipt.runId == var.run_id &&
    local.probe_clearance_receipt.vpcId == module.network.vpc_id &&
    local.probe_clearance_receipt.probeInstanceId == var.verified_probe_instance_id &&
    local.probe_clearance_receipt.instanceState == "terminated" &&
    can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", local.probe_clearance_receipt.clearedAt)),
    false,
  )

  bundle_manifest = try(
    jsondecode(nonsensitive(data.aws_s3_object.bundle_manifest[0].body)),
    null,
  )
  bundle_checksum_line = local.services_enabled ? trimspace(try(
    nonsensitive(data.aws_s3_object.bundle_checksum[0].body),
    "",
  )) : ""
  bundle_release_valid = !local.services_enabled || try(
    local.bundle_manifest.schemaVersion == 1 &&
    local.bundle_manifest.commit == var.bundle_commit &&
    local.bundle_manifest.archive == local.bundle_archive_name &&
    local.bundle_manifest.sha256 == var.bundle_sha256 &&
    local.bundle_manifest.files == local.service_bundle_files &&
    local.bundle_checksum_line == "${var.bundle_sha256}  ${local.bundle_archive_name}",
    false,
  )

  dataset_manifest_keys = toset([
    "schemaVersion", "releaseKind", "datasetRelease", "datasetRunId", "releaseTuple", "source", "mysql",
    "couponPreparation", "kafka", "search",
  ])
  dataset_kafka_topics = toset([
    "PAYMENT_OPERATION.events",
    "PAYMENT_OPERATION.events.RETRY",
    "PAYMENT_OPERATION.events.DLT",
    "ACCOMMODATION_INDEX.events",
    "ACCOMMODATION_INDEX.events.RETRY",
    "ACCOMMODATION_INDEX.events.DLT",
    "ACCOMMODATION_CACHE.events",
    "ACCOMMODATION_CACHE.events.RETRY",
    "ACCOMMODATION_CACHE.events.DLT",
    "OPERATOR_ALERT.events",
    "OPERATOR_ALERT.events.RETRY",
    "OPERATOR_ALERT.events.DLT",
  ])
  dataset_profile_contracts = {
    "production-skew-v1" = {
      production_spec_key = "benchmark/production-skew-v1.json"
      dump_storage_gib    = 20
      budgets = {
        accommodations  = 50000
        members         = 200000
        reservations    = 2500000
        reviews         = 1000000
        activeWishlists = 400000
        wishlistLinks   = 1500000
      }
    }
    "production-skew-large-v1" = {
      production_spec_key = "benchmark/production-skew-large-v1.json"
      dump_storage_gib    = 100
      budgets = {
        accommodations  = 200000
        members         = 800000
        reservations    = 10000000
        reviews         = 4000000
        activeWishlists = 1600000
        wishlistLinks   = 6000000
      }
    }
  }
  dataset_profile_version  = try(local.dataset_manifest.releaseTuple.profileVersion, "")
  dataset_profile_contract = try(local.dataset_profile_contracts[local.dataset_profile_version], null)
  dataset_dump_storage_gib = try(local.dataset_profile_contract.dump_storage_gib, 20)
  dataset_final_table_minimum_rows = {
    accommodation          = try(local.dataset_profile_contract.budgets.accommodations, -1)
    member                 = try(local.dataset_profile_contract.budgets.members, -1)
    reservation            = try(local.dataset_profile_contract.budgets.reservations, -1)
    review                 = try(local.dataset_profile_contract.budgets.reviews, -1)
    wishlist               = try(local.dataset_profile_contract.budgets.activeWishlists, -1)
    wishlist_accommodation = try(local.dataset_profile_contract.budgets.wishlistLinks, -1)
  }
  dataset_production_spec = local.services_enabled ? try(
    jsondecode(nonsensitive(data.aws_s3_object.dataset_production_spec[0].body)),
    null,
  ) : null
  dataset_production_spec_budgets = try({
    accommodations  = local.dataset_production_spec.targets.accommodations.rowBudget
    members         = local.dataset_production_spec.targets.members.rowBudget
    reservations    = local.dataset_production_spec.targets.reservations.rowBudget
    reviews         = local.dataset_production_spec.targets.reviews.rowBudget
    activeWishlists = local.dataset_production_spec.targets.activeWishlists.rowBudget
    wishlistLinks   = local.dataset_production_spec.targets.wishlistLinks.rowBudget
  }, {})
  dataset_release_valid = !local.services_enabled || try(
    sha256(nonsensitive(data.aws_s3_object.dataset_manifest[0].body)) == var.dataset_manifest_sha256 &&
    toset(keys(local.dataset_manifest)) == local.dataset_manifest_keys &&
    local.dataset_manifest.schemaVersion == 2 &&
    local.dataset_manifest.datasetRelease == var.dataset_release &&
    local.dataset_release_kind == "pipeline-rehearsal" &&
    can(regex("^([a-z0-9][a-z0-9._-]{2,63}|[0-9]{8}T[0-9]{6}Z-[0-9a-f]{8})$", local.dataset_manifest.datasetRunId)) &&
    toset(keys(local.dataset_manifest.releaseTuple)) == toset([
      "datasetVersion", "worldVersion", "calibrationVersion", "profileVersion", "generatorVersion",
      "dumpSha256", "migrationChecksumSha256", "schemaFingerprintSha256", "manifestSha256",
      "validatorSha256", "calibrationSha256", "specSha256", "qualificationSha256",
      "databaseFingerprintSha256", "attestationSha256", "finalWorldFingerprintSha256",
      "baseWorldFingerprintSha256", "distributionFingerprintSha256", "targetFingerprintSha256",
      "inventoryFingerprintSha256",
    ]) &&
    local.dataset_manifest.releaseTuple.datasetVersion == "benchmark-dataset-v2" &&
    local.dataset_manifest.releaseTuple.worldVersion == "world-v2" &&
    local.dataset_manifest.releaseTuple.calibrationVersion == "source-calibration-v1" &&
    contains(keys(local.dataset_profile_contracts), local.dataset_profile_version) &&
    local.dataset_manifest.releaseTuple.generatorVersion == "production-skew-generator-v1" &&
    alltrue([for key, value in local.dataset_manifest.releaseTuple : endswith(key, "Sha256") ? can(regex("^[0-9a-f]{64}$", value)) : true]) &&
    toset(keys(local.dataset_manifest.source)) == toset([
      "datasetVersion", "worldVersion", "etlCommit", "seed", "profile", "manifestVersion",
      "canonicalPayloadSha256", "legacyBenchmarkManifestKey", "legacyBenchmarkManifestSha256",
      "benchmarkDatasetManifestKey", "benchmarkDatasetManifestSha256", "validatorKey", "validatorSha256",
      "calibrationKey", "calibrationSha256", "productionSpecKey", "productionSpecSha256",
      "generationQualificationKey", "generationQualificationSha256", "databaseFingerprintKey",
      "databaseFingerprintSha256", "attestationKey", "attestationSha256", "sourceInventorySha256",
    ]) &&
    can(regex("^[0-9a-f]{40}$", local.dataset_manifest.source.etlCommit)) &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.source.canonicalPayloadSha256)) &&
    local.dataset_manifest.source.datasetVersion == "benchmark-dataset-v2" &&
    local.dataset_manifest.source.worldVersion == "world-v2" &&
    local.dataset_manifest.source.legacyBenchmarkManifestKey == "benchmark/manifest.json" &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.source.legacyBenchmarkManifestSha256)) &&
    local.dataset_manifest.source.benchmarkDatasetManifestKey == "benchmark/dataset-manifest.json" &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.source.benchmarkDatasetManifestSha256)) &&
    local.dataset_manifest.source.validatorKey == "benchmark/validate-benchmark-dataset-v2.jq" &&
    local.dataset_manifest.source.calibrationKey == "benchmark/source-calibration-v1.json" &&
    local.dataset_manifest.source.productionSpecKey == local.dataset_profile_contract.production_spec_key &&
    data.aws_s3_object.dataset_production_spec[0].key == "${local.dataset_prefix}/${local.dataset_profile_contract.production_spec_key}" &&
    sha256(nonsensitive(data.aws_s3_object.dataset_production_spec[0].body)) == local.dataset_manifest.source.productionSpecSha256 &&
    local.dataset_production_spec.profileVersion == local.dataset_profile_version &&
    local.dataset_production_spec.provenance.generatorVersion == local.dataset_manifest.releaseTuple.generatorVersion &&
    local.dataset_production_spec_budgets == local.dataset_profile_contract.budgets &&
    alltrue([
      for target in values(local.dataset_production_spec.targets) :
      try(target.rowBudget, null) == null || try(
        target.tolerance.absoluteRows == 0 && target.tolerance.relativePercent == 0,
        false,
      )
    ]) &&
    local.dataset_manifest.source.generationQualificationKey == "benchmark/generation-qualification-v1.json" &&
    local.dataset_manifest.source.databaseFingerprintKey == "mysql/database-fingerprint.tsv" &&
    local.dataset_manifest.source.attestationKey == "attestation/restore.json" &&
    local.dataset_manifest.releaseTuple.manifestSha256 == local.dataset_manifest.source.benchmarkDatasetManifestSha256 &&
    local.dataset_manifest.releaseTuple.validatorSha256 == local.dataset_manifest.source.validatorSha256 &&
    local.dataset_manifest.releaseTuple.calibrationSha256 == local.dataset_manifest.source.calibrationSha256 &&
    local.dataset_manifest.releaseTuple.specSha256 == local.dataset_manifest.source.productionSpecSha256 &&
    local.dataset_manifest.releaseTuple.qualificationSha256 == local.dataset_manifest.source.generationQualificationSha256 &&
    local.dataset_manifest.releaseTuple.databaseFingerprintSha256 == local.dataset_manifest.source.databaseFingerprintSha256 &&
    local.dataset_manifest.releaseTuple.attestationSha256 == local.dataset_manifest.source.attestationSha256 &&
    local.dataset_manifest.mysql.dumpKey == "mysql/airbob.sql.zst" &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.mysql.dumpSha256)) &&
    local.dataset_manifest.mysql.flywayVersion == "27" &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.mysql.migrationChecksumSha256)) &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.mysql.schemaFingerprintSha256)) &&
    local.dataset_manifest.mysql.timezone == "UTC" &&
    local.dataset_manifest.mysql.outboxPolicy == "absent" &&
    contains(keys(local.dataset_expected_table_rows), "flyway_schema_history") &&
    local.dataset_expected_table_rows.flyway_schema_history == 27 &&
    contains(keys(local.dataset_expected_table_rows), "outbox") &&
    contains(keys(local.dataset_expected_table_rows), "accommodation") &&
    contains(keys(local.dataset_expected_table_rows), "accommodation_inventory_day") &&
    contains(keys(local.dataset_expected_table_rows), "reservation") &&
    alltrue([
      for table, minimum_rows in local.dataset_final_table_minimum_rows : try(
        local.dataset_expected_table_rows[table] >= minimum_rows &&
        floor(local.dataset_expected_table_rows[table]) == local.dataset_expected_table_rows[table],
        false,
      )
    ]) &&
    length(local.dataset_manifest.kafka.topics) == 12 &&
    toset([for topic in local.dataset_manifest.kafka.topics : topic.name]) == local.dataset_kafka_topics &&
    alltrue([
      for topic in local.dataset_manifest.kafka.topics :
      toset(keys(topic)) == toset(["name", "partitions", "retentionMs"]) &&
      topic.partitions == 3 &&
      topic.retentionMs == 86400000
    ]) &&
    local.dataset_manifest.releaseTuple.dumpSha256 == local.dataset_manifest.mysql.dumpSha256 &&
    local.dataset_manifest.releaseTuple.migrationChecksumSha256 == local.dataset_manifest.mysql.migrationChecksumSha256 &&
    local.dataset_manifest.releaseTuple.schemaFingerprintSha256 == local.dataset_manifest.mysql.schemaFingerprintSha256 &&
    (
      (!local.dataset_search_enabled && toset(keys(local.dataset_manifest.search)) == toset(["enabled"])) ||
      (local.dataset_search_enabled &&
        toset(keys(local.dataset_manifest.search)) == toset([
          "enabled", "snapshotReferenceKey", "repository", "elasticsearchVersion", "imageDigest",
          "requiredPlugins", "logicalAlias", "snapshotIndex", "documentCount", "mappingSha256",
          "databaseAccommodationIdsSha256", "elasticsearchAccommodationIdsSha256",
          "databaseDocumentIdentityPairsSha256", "elasticsearchDocumentIdentityPairsSha256",
          "contentFingerprintSha256",
        ]) &&
        local.dataset_manifest.search.snapshotReferenceKey == "elasticsearch/snapshot-reference.json" &&
        local.dataset_manifest.search.repository == "airbob-dataset-readonly" &&
        local.dataset_manifest.search.requiredPlugins == ["analysis-nori", "repository-s3"] &&
        local.dataset_manifest.search.logicalAlias == "accommodations" &&
        can(regex("^sha256:[0-9a-f]{64}$", local.dataset_manifest.search.imageDigest)) &&
        can(regex("^[a-z0-9][a-z0-9._-]{2,254}$", local.dataset_manifest.search.snapshotIndex)) &&
        local.dataset_manifest.search.documentCount >= 0 &&
        floor(local.dataset_manifest.search.documentCount) == local.dataset_manifest.search.documentCount &&
        local.dataset_manifest.search.databaseAccommodationIdsSha256 == local.dataset_manifest.search.elasticsearchAccommodationIdsSha256 &&
        local.dataset_manifest.search.databaseDocumentIdentityPairsSha256 == local.dataset_manifest.search.elasticsearchDocumentIdentityPairsSha256 &&
        alltrue([
          for value in [
            local.dataset_manifest.search.mappingSha256,
            local.dataset_manifest.search.databaseAccommodationIdsSha256,
            local.dataset_manifest.search.elasticsearchAccommodationIdsSha256,
            local.dataset_manifest.search.databaseDocumentIdentityPairsSha256,
            local.dataset_manifest.search.elasticsearchDocumentIdentityPairsSha256,
            local.dataset_manifest.search.contentFingerprintSha256,
          ] : can(regex("^[0-9a-f]{64}$", value))
      ]))
    ) &&
    length(regexall("(?i)\\\"[^\\\"]*(password|passwd|secret|credential|authorization|token|session.?id|cookie|api.?key|access.?key|private.?key|service.?account|raw.?pii)[^\\\"]*\\\"[[:space:]]*:", jsonencode(local.dataset_manifest))) == 0 &&
    length(regexall("(?i)-----BEGIN[[:space:]].*PRIVATE KEY-----|(AKIA|ASIA)[0-9A-Z]{16}|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", jsonencode(local.dataset_manifest))) == 0 &&
    (
      !local.dataset_search_enabled ||
      local.dataset_manifest.search.imageDigest == split("@", var.infra_image_references["ELASTICSEARCH_IMAGE"])[1]
    ),
    false,
  )
  dataset_snapshot_valid = !local.services_enabled || var.database_bootstrap != "snapshot" || try(
    local.lab_contract.approved_rds_snapshot_identifier == var.rds_snapshot_identifier &&
    data.aws_db_snapshot.dataset[0].status == "available" &&
    data.aws_db_snapshot.dataset[0].engine == "mysql" &&
    data.aws_db_snapshot.dataset[0].engine_version == var.rds_engine_version &&
    data.aws_db_snapshot.dataset[0].encrypted &&
    data.aws_db_snapshot.dataset[0].allocated_storage == local.dataset_dump_storage_gib &&
    data.aws_db_snapshot.dataset[0].storage_type == "gp3" &&
    data.aws_db_snapshot.dataset[0].iops == 3000 &&
    data.aws_db_snapshot.dataset[0].tags.SourceLabRunId == var.rds_snapshot_source_run_id &&
    data.aws_db_snapshot.dataset[0].tags.SourceRdsResourceId == var.rds_snapshot_source_resource_id &&
    data.aws_db_snapshot.dataset[0].tags.PromotionReceiptSchemaVersion == "2" &&
    data.aws_db_snapshot.dataset[0].tags.DataBootstrapKey == "data-bootstrap/${var.rds_snapshot_source_run_id}/${var.dataset_release}.json" &&
    can(regex("^[0-9a-f]{64}$", data.aws_db_snapshot.dataset[0].tags.DataBootstrapVersionIdSha256)) &&
    can(regex("^[0-9a-f]{64}$", data.aws_db_snapshot.dataset[0].tags.DataBootstrapSha256)) &&
    data.aws_db_snapshot.dataset[0].tags.DirectReadinessKey == "measurements/${var.rds_snapshot_source_run_id}/direct-readiness.json" &&
    can(regex("^[0-9a-f]{64}$", data.aws_db_snapshot.dataset[0].tags.DirectReadinessVersionIdSha256)) &&
    can(regex("^[0-9a-f]{64}$", data.aws_db_snapshot.dataset[0].tags.DirectReadinessSha256)) &&
    data.aws_db_snapshot.dataset[0].tags.Project == "airbob" &&
    data.aws_db_snapshot.dataset[0].tags.Environment == "performance-lab" &&
    data.aws_db_snapshot.dataset[0].tags.Stack == "dataset" &&
    data.aws_db_snapshot.dataset[0].tags.ManagedBy == "dataset-publisher" &&
    data.aws_db_snapshot.dataset[0].tags.Persistence == "persistent" &&
    data.aws_db_snapshot.dataset[0].tags.DatasetRelease == var.dataset_release &&
    data.aws_db_snapshot.dataset[0].tags.DatasetRunId == local.dataset_manifest.datasetRunId &&
    data.aws_db_snapshot.dataset[0].tags.DumpSha256 == local.dataset_manifest.mysql.dumpSha256 &&
    data.aws_db_snapshot.dataset[0].tags.FlywayVersion == local.dataset_manifest.mysql.flywayVersion &&
    data.aws_db_snapshot.dataset[0].tags.ManifestSha256 == var.dataset_manifest_sha256,
    false,
  )

  data_bootstrap_receipt = try(
    jsondecode(nonsensitive(data.aws_s3_object.data_bootstrap_receipt[0].body)),
    null,
  )
  data_bootstrap_receipt_valid = !local.data_ready || try(
    toset(keys(local.data_bootstrap_receipt)) == toset([
      "schemaVersion", "runId", "datasetRelease", "datasetRunId", "releaseKind",
      "databaseBootstrap", "dumpSha256", "flywayVersion", "migrationChecksumSha256",
      "schemaFingerprintSha256", "datasetManifestSha256", "validatorSha256",
      "benchmarkDatasetManifestSha256", "calibrationSha256", "productionSpecSha256",
      "qualificationSha256", "databaseFingerprintSha256", "restoreAttestationSha256",
      "finalWorldFingerprintSha256", "baseWorldFingerprintSha256", "distributionFingerprintSha256",
      "targetFingerprintSha256", "inventoryFingerprintSha256", "semanticAttestationSha256",
      "rdsResourceId", "rdsEngineVersion", "outboxState", "redisState", "kafkaTopics",
      "connectorState", "searchState", "verifiedAt",
    ]) &&
    local.data_bootstrap_receipt.schemaVersion == 2 &&
    local.data_bootstrap_receipt.runId == var.run_id &&
    local.data_bootstrap_receipt.datasetRelease == var.dataset_release &&
    local.data_bootstrap_receipt.datasetRunId == local.dataset_manifest.datasetRunId &&
    local.data_bootstrap_receipt.releaseKind == local.dataset_release_kind &&
    local.data_bootstrap_receipt.databaseBootstrap == var.database_bootstrap &&
    local.data_bootstrap_receipt.dumpSha256 == local.dataset_manifest.mysql.dumpSha256 &&
    local.data_bootstrap_receipt.flywayVersion == "27" &&
    local.data_bootstrap_receipt.migrationChecksumSha256 == local.dataset_manifest.mysql.migrationChecksumSha256 &&
    local.data_bootstrap_receipt.schemaFingerprintSha256 == local.dataset_manifest.mysql.schemaFingerprintSha256 &&
    local.data_bootstrap_receipt.datasetManifestSha256 == var.dataset_manifest_sha256 &&
    local.data_bootstrap_receipt.validatorSha256 == local.dataset_manifest.releaseTuple.validatorSha256 &&
    local.data_bootstrap_receipt.benchmarkDatasetManifestSha256 == local.dataset_manifest.releaseTuple.manifestSha256 &&
    local.data_bootstrap_receipt.calibrationSha256 == local.dataset_manifest.releaseTuple.calibrationSha256 &&
    local.data_bootstrap_receipt.productionSpecSha256 == local.dataset_manifest.releaseTuple.specSha256 &&
    local.data_bootstrap_receipt.qualificationSha256 == local.dataset_manifest.releaseTuple.qualificationSha256 &&
    local.data_bootstrap_receipt.databaseFingerprintSha256 == local.dataset_manifest.releaseTuple.databaseFingerprintSha256 &&
    local.data_bootstrap_receipt.restoreAttestationSha256 == local.dataset_manifest.releaseTuple.attestationSha256 &&
    local.data_bootstrap_receipt.finalWorldFingerprintSha256 == local.dataset_manifest.releaseTuple.finalWorldFingerprintSha256 &&
    local.data_bootstrap_receipt.baseWorldFingerprintSha256 == local.dataset_manifest.releaseTuple.baseWorldFingerprintSha256 &&
    local.data_bootstrap_receipt.distributionFingerprintSha256 == local.dataset_manifest.releaseTuple.distributionFingerprintSha256 &&
    local.data_bootstrap_receipt.targetFingerprintSha256 == local.dataset_manifest.releaseTuple.targetFingerprintSha256 &&
    local.data_bootstrap_receipt.inventoryFingerprintSha256 == local.dataset_manifest.releaseTuple.inventoryFingerprintSha256 &&
    can(regex("^[0-9a-f]{64}$", local.data_bootstrap_receipt.semanticAttestationSha256)) &&
    local.data_bootstrap_receipt.rdsResourceId == module.rds[0].resource_id &&
    local.data_bootstrap_receipt.rdsEngineVersion == var.rds_engine_version &&
    local.data_bootstrap_receipt.outboxState == "empty" &&
    contains(["empty", "coupon-prepared"], local.data_bootstrap_receipt.redisState) &&
    local.data_bootstrap_receipt.kafkaTopics == local.dataset_manifest.kafka.topics &&
    local.data_bootstrap_receipt.connectorState == "RUNNING" &&
    local.data_bootstrap_receipt.searchState == (local.dataset_search_enabled ? "restored" : "skipped") &&
    can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", local.data_bootstrap_receipt.verifiedAt)),
    false,
  )
}

check "foundation_boundary" {
  assert {
    condition = (
      local.caller_identity_valid &&
      local.contract_schema_valid &&
      local.state_boundaries_valid &&
      local.operational_contract_valid &&
      local.ecr_contract_valid
    )
    error_message = "Phase 2 requires the exact validated foundation contract and pinned caller boundary."
  }
}

check "phase_transition" {
  assert {
    condition     = local.network_receipt_valid && local.probe_clearance_valid
    error_message = "Later phases require exact egress and probe-termination receipts for this VPC, run, AMI, and probe."
  }
}

check "bootstrap_document_budget" {
  assert {
    condition     = !local.services_enabled || length(local.bootstrap_data_command) <= 45000
    error_message = "The compressed data-bootstrap SSM command must remain at or below 45KB."
  }
}

resource "terraform_data" "network_receipt_gate" {
  count = local.receipt_required ? 1 : 0

  input = var.verified_probe_instance_id

  lifecycle {
    precondition {
      condition     = local.network_receipt_valid
      error_message = "Refusing to remove the probe or create services without the exact network egress receipt."
    }
  }
}

resource "terraform_data" "probe_clearance_gate" {
  count = local.services_enabled ? 1 : 0

  input = var.verified_probe_instance_id

  lifecycle {
    precondition {
      condition     = local.probe_clearance_valid
      error_message = "Refusing to create services until the verified probe is confirmed terminated after a probe-cleared apply."
    }
  }
}

resource "terraform_data" "service_release_gate" {
  count = local.services_enabled ? 1 : 0

  input = var.bundle_commit

  lifecycle {
    precondition {
      condition     = local.bundle_release_valid && local.phase2_images_valid
      error_message = "Refusing to create services without the exact immutable bundle and image digest release."
    }
  }
}

resource "terraform_data" "dataset_release_gate" {
  count = local.services_enabled ? 1 : 0

  input = var.dataset_release

  lifecycle {
    precondition {
      condition     = local.dataset_release_valid && local.dataset_snapshot_valid
      error_message = "Refusing to create RDS without the exact V27 dataset manifest and, when selected, matching snapshot tags."
    }
  }
}

resource "terraform_data" "data_bootstrap_gate" {
  count = local.data_ready ? 1 : 0

  input = var.dataset_release

  lifecycle {
    precondition {
      condition     = local.data_bootstrap_receipt_valid
      error_message = "Refusing to attest data-ready without the exact RDS, Redis, Kafka, Debezium, and search bootstrap receipt."
    }
  }
}

check "service_release" {
  assert {
    condition     = local.bundle_release_valid && local.phase2_images_valid
    error_message = "The services phase requires an immutable nineteen-file bundle release and nine exact ECR digest references."
  }
}

check "app_release" {
  assert {
    condition     = local.app_image_valid
    error_message = "The services phase requires the exact immutable application image from the approved ECR repository."
  }
}

check "app_capacity_contract" {
  assert {
    condition = (
      (!var.app_enabled || (local.data_ready && local.data_bootstrap_receipt_valid)) &&
      (!var.load_generator_enabled || var.app_enabled) &&
      (var.measurement_policy != "integrated-smoke" || var.mode == "performance") &&
      (
        (var.mode == "performance" && var.request_count_per_target_per_minute == null) ||
        (
          var.mode == "scaling" &&
          (!var.app_enabled || var.request_count_per_target_per_minute != null) &&
          var.measurement_policy == "isolated-read"
        )
      )
    )
    error_message = "App capacity requires data-ready; integrated smoke is performance-only; scaling requires isolated-read with a baseline request target; and load generation requires an enabled app."
  }
}

check "dataset_release" {
  assert {
    condition     = local.dataset_release_valid && local.dataset_snapshot_valid
    error_message = "Phase 3 requires an immutable V27 dataset release and a matching optional RDS snapshot."
  }
}

check "data_bootstrap" {
  assert {
    condition     = local.data_bootstrap_receipt_valid
    error_message = "The data-ready phase requires an exact ordered bootstrap receipt."
  }
}
