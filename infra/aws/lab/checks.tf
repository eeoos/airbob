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

  dataset_manifest_keys = local.dataset_release_kind == "evidence" ? toset([
    "schemaVersion", "releaseKind", "datasetRelease", "datasetRunId", "source", "mysql",
    "couponPreparation", "kafka", "search", "evidence",
    ]) : toset([
    "schemaVersion", "releaseKind", "datasetRelease", "datasetRunId", "source", "mysql",
    "couponPreparation", "kafka", "search",
  ])
  dataset_release_valid = !local.services_enabled || try(
    sha256(nonsensitive(data.aws_s3_object.dataset_manifest[0].body)) == var.dataset_manifest_sha256 &&
    toset(keys(local.dataset_manifest)) == local.dataset_manifest_keys &&
    local.dataset_manifest.schemaVersion == 1 &&
    local.dataset_manifest.datasetRelease == var.dataset_release &&
    contains(["pipeline-rehearsal", "evidence"], local.dataset_release_kind) &&
    can(regex("^[a-z0-9][a-z0-9._-]{2,63}$", local.dataset_manifest.datasetRunId)) &&
    toset(keys(local.dataset_manifest.source)) == toset([
      "datasetVersion", "etlCommit", "seed", "profile", "manifestVersion",
      "canonicalPayloadSha256", "benchmarkManifestKey", "benchmarkManifestSha256",
    ]) &&
    can(regex("^[0-9a-f]{40}$", local.dataset_manifest.source.etlCommit)) &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.source.canonicalPayloadSha256)) &&
    local.dataset_manifest.source.benchmarkManifestKey == "benchmark/manifest.json" &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.source.benchmarkManifestSha256)) &&
    local.dataset_manifest.mysql.dumpKey == "mysql/airbob.sql.zst" &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.mysql.dumpSha256)) &&
    local.dataset_manifest.mysql.flywayVersion == "17" &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.mysql.migrationChecksumSha256)) &&
    can(regex("^[0-9a-f]{64}$", local.dataset_manifest.mysql.schemaFingerprintSha256)) &&
    local.dataset_manifest.mysql.timezone == "UTC" &&
    contains(["absent", "truncate-after-import"], local.dataset_manifest.mysql.outboxPolicy) &&
    contains(keys(local.dataset_expected_table_rows), "flyway_schema_history") &&
    local.dataset_expected_table_rows.flyway_schema_history == 17 &&
    contains(keys(local.dataset_expected_table_rows), "outbox") &&
    contains(keys(local.dataset_expected_table_rows), "accommodation") &&
    length(local.dataset_manifest.kafka.topics) == 3 &&
    (
      (local.dataset_release_kind == "pipeline-rehearsal" && local.dataset_manifest.source.datasetVersion == "nplus1-v1") ||
      (local.dataset_release_kind == "evidence" && local.dataset_manifest.source.datasetVersion == "traffic-v1" && local.dataset_search_enabled)
    ) &&
    (
      !local.dataset_search_enabled ||
      local.dataset_manifest.search.imageDigest == split("@", var.infra_image_references["ELASTICSEARCH_IMAGE"])[1]
    ),
    false,
  )
  dataset_snapshot_valid = !local.services_enabled || var.database_bootstrap != "snapshot" || try(
    data.aws_db_snapshot.dataset[0].status == "available" &&
    data.aws_db_snapshot.dataset[0].engine == "mysql" &&
    data.aws_db_snapshot.dataset[0].engine_version == var.rds_engine_version &&
    data.aws_db_snapshot.dataset[0].encrypted &&
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
      "schemaFingerprintSha256", "datasetManifestSha256",
      "rdsResourceId", "rdsEngineVersion", "outboxState", "redisState", "kafkaTopics",
      "connectorState", "searchState", "verifiedAt",
    ]) &&
    local.data_bootstrap_receipt.schemaVersion == 1 &&
    local.data_bootstrap_receipt.runId == var.run_id &&
    local.data_bootstrap_receipt.datasetRelease == var.dataset_release &&
    local.data_bootstrap_receipt.datasetRunId == local.dataset_manifest.datasetRunId &&
    local.data_bootstrap_receipt.releaseKind == local.dataset_release_kind &&
    local.data_bootstrap_receipt.databaseBootstrap == var.database_bootstrap &&
    local.data_bootstrap_receipt.dumpSha256 == local.dataset_manifest.mysql.dumpSha256 &&
    local.data_bootstrap_receipt.flywayVersion == "17" &&
    local.data_bootstrap_receipt.migrationChecksumSha256 == local.dataset_manifest.mysql.migrationChecksumSha256 &&
    local.data_bootstrap_receipt.schemaFingerprintSha256 == local.dataset_manifest.mysql.schemaFingerprintSha256 &&
    local.data_bootstrap_receipt.datasetManifestSha256 == var.dataset_manifest_sha256 &&
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
      error_message = "Refusing to create RDS without the exact V17 dataset manifest and, when selected, matching snapshot tags."
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
    error_message = "App capacity requires data-ready, integrated smoke is performance-only, scaling is isolated-read with a baseline request target, and load generation requires an enabled app."
  }
}

check "dataset_release" {
  assert {
    condition     = local.dataset_release_valid && local.dataset_snapshot_valid
    error_message = "Phase 3 requires an immutable V17 dataset release and a matching optional RDS snapshot."
  }
}

check "data_bootstrap" {
  assert {
    condition     = local.data_bootstrap_receipt_valid
    error_message = "The data-ready phase requires an exact ordered bootstrap receipt."
  }
}
