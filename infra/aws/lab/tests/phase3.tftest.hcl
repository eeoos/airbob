mock_provider "aws" {
  override_data {
    target = data.aws_caller_identity.current
    values = {
      account_id = "942632789808"
      arn        = "arn:aws:iam::942632789808:role/lab-test"
      user_id    = "AROATEST"
    }
  }

  override_data {
    target = data.aws_region.current
    values = { region = "ap-northeast-2" }
  }

  override_data {
    target = data.aws_ssm_parameter.foundation_contract
    values = {
      name  = "/airbob/performance-lab/foundation/lab-contract"
      type  = "String"
      value = file("tests/fixtures/lab-contract.json")
    }
  }

  override_data {
    target = data.aws_ami.selected
    values = {
      id           = "ami-0123456789abcdef0"
      architecture = "x86_64"
      owner_id     = "137112412989"
      state        = "available"
    }
  }
}

variables {
  run_id                     = "phase3-test"
  expires_at                 = "1893456000"
  fencing_token              = 42
  ami_id                     = "ami-0123456789abcdef0"
  deployment_phase           = "services"
  verified_probe_instance_id = "i-0123456789abcdef0"
  bundle_commit              = "0123456789abcdef0123456789abcdef01234567"
  bundle_sha256              = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  dataset_release            = "rehearsal-v20"
  dataset_manifest_sha256    = "bff2552f6811e187527bf204cdb87f543826414c3085deab0a448a5123a5bfbc"
  database_bootstrap         = "dump"
  rds_engine_version         = "8.0.40"
  app_image_reference        = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-repo@sha256:9123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  infra_image_references = {
    REDIS_IMAGE                  = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/redis@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    REDIS_EXPORTER_IMAGE         = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/redis-exporter@sha256:1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    NODE_EXPORTER_IMAGE          = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/node-exporter@sha256:2123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    KAFKA_IMAGE                  = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/kafka@sha256:3123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    DEBEZIUM_IMAGE               = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/debezium@sha256:4123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    ELASTICSEARCH_IMAGE          = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/elasticsearch@sha256:5123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    ELASTICSEARCH_EXPORTER_IMAGE = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/elasticsearch-exporter@sha256:6123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    PROMETHEUS_IMAGE             = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/prometheus@sha256:7123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    GRAFANA_IMAGE                = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/grafana@sha256:8123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }
}

override_resource {
  target          = module.network.aws_vpc.this
  override_during = plan
  values          = { id = "vpc-0123456789abcdef0" }
}

override_resource {
  target          = module.network.aws_route_table.private["primary"]
  override_during = plan
  values          = { id = "rtb-0123456789abcdef0" }
}

override_resource {
  target          = module.rds[0].aws_db_instance.this
  override_during = plan
  values = {
    id          = "airbob-phase3-test"
    arn         = "arn:aws:rds:ap-northeast-2:942632789808:db:airbob-phase3-test"
    address     = "airbob-phase3-test.abcdefghijkl.ap-northeast-2.rds.amazonaws.com"
    port        = 3306
    resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
    master_user_secret = [{
      kms_key_id    = "arn:aws:kms:ap-northeast-2:942632789808:key/11111111-2222-3333-4444-555555555555"
      secret_arn    = "arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-test"
      secret_status = "active"
    }]
  }
}

override_data {
  target          = data.aws_s3_object.network_receipt[0]
  override_during = plan
  values = {
    body = jsonencode({
      schemaVersion       = 1, runId = "phase3-test", vpcId = "vpc-0123456789abcdef0",
      primaryRouteTableId = "rtb-0123456789abcdef0", probeInstanceId = "i-0123456789abcdef0",
      amiId               = "ami-0123456789abcdef0", s3Gateway = "verified", ecrApi = "verified",
      ssmApi              = "verified", secretsManagerApi = "verified", verifiedAt = "2030-01-01T00:00:00Z"
    })
  }
}

override_data {
  target          = data.aws_s3_object.probe_clearance_receipt[0]
  override_during = plan
  values = {
    body = jsonencode({
      schemaVersion   = 1, runId = "phase3-test", vpcId = "vpc-0123456789abcdef0",
      probeInstanceId = "i-0123456789abcdef0", instanceState = "terminated", clearedAt = "2030-01-01T00:05:00Z"
    })
  }
}

override_data {
  target          = data.aws_s3_object.bundle_manifest[0]
  override_during = plan
  values = {
    body = jsonencode({
      schemaVersion = 1,
      commit        = "0123456789abcdef0123456789abcdef01234567",
      archive       = "airbob-service-bundles-0123456789abcdef0123456789abcdef01234567.tar.gz",
      sha256        = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      files         = jsondecode(file("../bundles/manifest.json")).files
    })
  }
}

override_data {
  target          = data.aws_s3_object.bundle_checksum[0]
  override_during = plan
  values = {
    body = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef  airbob-service-bundles-0123456789abcdef0123456789abcdef01234567.tar.gz\n"
  }
}

override_data {
  target          = data.aws_s3_object.dataset_manifest[0]
  override_during = plan
  values = {
    body = file("tests/fixtures/dataset-manifest.json")
  }
}

override_data {
  target          = data.aws_s3_object.data_bootstrap_receipt[0]
  override_during = plan
  values = {
    body = jsonencode({
      schemaVersion           = 1
      runId                   = "phase3-test"
      datasetRelease          = "rehearsal-v20"
      datasetRunId            = "20260816T001530Z-12345678"
      releaseKind             = "pipeline-rehearsal"
      databaseBootstrap       = "dump"
      dumpSha256              = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
      flywayVersion           = "20"
      migrationChecksumSha256 = "4444444444444444444444444444444444444444444444444444444444444444"
      schemaFingerprintSha256 = "5555555555555555555555555555555555555555555555555555555555555555"
      datasetManifestSha256   = "bff2552f6811e187527bf204cdb87f543826414c3085deab0a448a5123a5bfbc"
      rdsResourceId           = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
      rdsEngineVersion        = "8.0.40"
      outboxState             = "empty"
      redisState              = "empty"
      kafkaTopics             = jsondecode(file("tests/fixtures/dataset-manifest.json")).kafka.topics
      connectorState          = "RUNNING"
      searchState             = "skipped"
      verifiedAt              = "2030-01-01T00:30:00Z"
    })
  }
}

run "create_dump_backed_rds_and_ordered_bootstrap" {
  command = plan

  assert {
    condition = (
      module.rds[0].contract.instance_class == "db.t3.micro" &&
      module.rds[0].contract.multi_az == false &&
      module.rds[0].contract.availability_zone == "ap-northeast-2a" &&
      module.rds[0].contract.snapshot_identifier == null &&
      module.rds[0].contract.manage_master_user_password == true &&
      module.rds[0].contract.backup_retention_period >= 1 &&
      aws_secretsmanager_secret.debezium[0].recovery_window_in_days == 0 &&
      output.phase3_contract.database_bootstrap == "dump" &&
      output.phase3_contract.release_kind == "pipeline-rehearsal" &&
      output.phase3_contract.search_enabled == false
    )
    error_message = "Dump mode must create the exact Single-AZ RDS and ordered secret-safe bootstrap contract."
  }
}

run "restore_rds_only_from_matching_snapshot" {
  command = plan

  variables {
    database_bootstrap      = "snapshot"
    rds_snapshot_identifier = "airbob-dataset-rehearsal-v20"
  }

  override_data {
    target          = data.aws_db_snapshot.dataset[0]
    override_during = plan
    values = {
      db_snapshot_identifier = "airbob-dataset-rehearsal-v20"
      engine                 = "mysql"
      engine_version         = "8.0.40"
      status                 = "available"
      encrypted              = true
      tags = {
        DatasetRelease = "rehearsal-v20"
        DatasetRunId   = "20260816T001530Z-12345678"
        DumpSha256     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
        FlywayVersion  = "20"
        ManifestSha256 = "bff2552f6811e187527bf204cdb87f543826414c3085deab0a448a5123a5bfbc"
      }
    }
  }

  assert {
    condition = (
      module.rds[0].contract.snapshot_identifier == "airbob-dataset-rehearsal-v20" &&
      output.phase3_contract.database_bootstrap == "snapshot"
    )
    error_message = "Snapshot mode must bind RDS creation to the prevalidated dataset snapshot."
  }
}

run "attest_only_the_exact_ordered_data_receipt" {
  command = plan

  variables {
    deployment_phase = "data-ready"
  }

  assert {
    condition = (
      output.phase3_contract.data_ready == true &&
      output.phase3_contract.data_bootstrap_receipt_key == "data-bootstrap/phase3-test/rehearsal-v20.json"
    )
    error_message = "data-ready must require the exact receipt for this run, RDS, release, and ordered service state."
  }
}

run "reject_stale_flyway_release" {
  command = plan

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        mysql = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql, { flywayVersion = "19" })
      }))
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "reject_incomplete_flyway_history" {
  command = plan

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        mysql = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql, {
          expectedTableRows = merge(
            jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql.expectedTableRows,
            { flyway_schema_history = 19 },
          )
        })
      }))
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "reject_unsafe_snapshot_identifier" {
  command = plan

  variables {
    database_bootstrap      = "snapshot"
    rds_snapshot_identifier = "airbob-dataset-invalid--snapshot"
  }

  expect_failures = [var.rds_snapshot_identifier]
}

run "bootstrap_app_infrastructure_at_zero_capacity" {
  command = plan

  assert {
    condition = (
      output.phase4_contract.app_enabled == false &&
      output.phase4_contract.mode == "performance" &&
      output.phase4_contract.capacity == { min = 0, desired = 0, max = 0 } &&
      output.phase4_contract.app_subnet_count == 1 &&
      output.phase4_contract.scaling_policy_count == 0 &&
      output.phase4_contract.load_generator_enabled == false &&
      output.phase4_contract.alb_https_only == true &&
      output.phase4_contract.alb_stickiness_enabled == false
    )
    error_message = "Services bootstrap must create HTTPS ALB and App ASG infrastructure at exact 0/0/0 capacity without scaling policies."
  }
}

run "enable_single_az_performance_capacity_after_data_ready" {
  command = plan

  variables {
    deployment_phase = "data-ready"
    app_enabled      = true
  }

  assert {
    condition = (
      output.phase4_contract.mode == "performance" &&
      output.phase4_contract.measurement_policy == "isolated-read" &&
      output.phase4_contract.capacity.min == 1 &&
      output.phase4_contract.capacity.desired == 1 &&
      output.phase4_contract.capacity.max == 1 &&
      output.phase4_contract.app_subnet_count == 1 &&
      output.phase4_contract.scaling_policy_count == 0 &&
      output.phase4_contract.instance_type == "c6i.large" &&
      output.phase4_contract.default_instance_warmup == 180 &&
      output.phase4_contract.refresh.min_healthy_percentage == 0 &&
      output.phase4_contract.refresh.max_healthy_percentage == 100 &&
      length(output.phase4_contract.refresh.checkpoint_percentages) == 0 &&
      output.phase4_contract.refresh.auto_rollback == true
    )
    error_message = "Performance mode must be single-AZ 1/1/1 with fixed warmup, replace-first refresh safety, and no scaling policy."
  }
}

run "enable_two_az_scaling_capacity_with_two_target_tracking_policies" {
  command = plan

  variables {
    deployment_phase                    = "data-ready"
    app_enabled                         = true
    mode                                = "scaling"
    measurement_policy                  = "isolated-read"
    request_count_per_target_per_minute = 1200
    load_generator_enabled              = true
  }

  assert {
    condition = (
      output.phase4_contract.capacity.min == 1 &&
      output.phase4_contract.capacity.desired == 1 &&
      output.phase4_contract.capacity.max == 4 &&
      output.phase4_contract.app_subnet_count == 2 &&
      output.phase4_contract.app_availability_zones == tolist(["ap-northeast-2a", "ap-northeast-2c"]) &&
      output.phase4_contract.scaling_policy_count == 2 &&
      output.phase4_contract.request_count_per_target_per_minute == 1200 &&
      output.phase4_contract.cpu_target_percent == 50 &&
      output.phase4_contract.load_generator_enabled == true &&
      output.phase4_contract.load_generator_instance_type == "c6i.xlarge" &&
      output.phase4_contract.load_generator_public_ipv4 == true &&
      toset(keys(aws_iam_role_policy.measurement_data_plane)) == toset(["debezium", "loadgen", "monitoring"]) &&
      alltrue([
        for role in ["debezium", "loadgen"] :
        contains(one([
          for statement in jsondecode(aws_iam_role_policy.measurement_data_plane[role].policy).Statement : statement
          if statement.Sid == "ReadMeasurementInputs"
        ]).Resource, "arn:aws:s3:::airbob-performance-lab-evidence-942632789808/measurement-inputs/phase3-test/*")
      ]) &&
      length([
        for statement in jsondecode(aws_iam_role_policy.measurement_data_plane["monitoring"].policy).Statement : statement
        if statement.Sid == "ReadMeasurementInputs"
      ]) == 0 &&
      one([
        for statement in jsondecode(aws_iam_role_policy.measurement_data_plane["loadgen"].policy).Statement : statement
        if statement.Sid == "ReadSelectedBenchmarkManifest"
      ]).Resource == "arn:aws:s3:::airbob-performance-lab-dataset-942632789808/datasets/rehearsal-v20/benchmark/manifest.json" &&
      alltrue([
        for policy in values(aws_iam_role_policy.measurement_data_plane) :
        one([
          for statement in jsondecode(policy.policy).Statement : statement
          if statement.Sid == "WriteMeasurementEvidence"
        ]).Resource == "arn:aws:s3:::airbob-performance-lab-evidence-942632789808/measurements/phase3-test/*"
      ]) &&
      output.phase4_contract.refresh.min_healthy_percentage == 100 &&
      output.phase4_contract.refresh.max_healthy_percentage == 200 &&
      output.phase4_contract.refresh.checkpoint_percentages[0] == 50 &&
      output.phase4_contract.refresh.checkpoint_percentages[1] == 100 &&
      output.phase4_contract.refresh.auto_rollback == true
    )
    error_message = "Scaling mode must be two-AZ 1/1/4 with CPU and baseline-derived ALB request target tracking plus the public no-ingress load generator."
  }
}

run "reject_scaling_without_baseline_request_target" {
  command = plan

  variables {
    deployment_phase   = "data-ready"
    app_enabled        = true
    mode               = "scaling"
    measurement_policy = "isolated-read"
  }

  expect_failures = [check.app_capacity_contract]
}

run "reject_integrated_smoke_in_scaling_mode" {
  command = plan

  variables {
    deployment_phase                    = "data-ready"
    app_enabled                         = true
    mode                                = "scaling"
    measurement_policy                  = "integrated-smoke"
    request_count_per_target_per_minute = 1200
  }

  expect_failures = [check.app_capacity_contract]
}

run "reject_app_capacity_before_data_ready" {
  command = plan

  variables {
    app_enabled = true
  }

  expect_failures = [check.app_capacity_contract]
}

run "reject_load_generator_without_enabled_app" {
  command = plan

  variables {
    load_generator_enabled = true
  }

  expect_failures = [check.app_capacity_contract]
}

run "reject_app_image_outside_approved_ecr_repository" {
  command = plan

  variables {
    app_image_reference = "public.example.invalid/airbob@sha256:9123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }

  expect_failures = [check.app_release]
}
