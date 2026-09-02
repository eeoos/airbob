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
  run_id                     = "lab-phase3-test"
  expires_at                 = "1893456000"
  fencing_token              = 42
  ami_id                     = "ami-0123456789abcdef0"
  dns_mode                   = "direct-only"
  alb_ingress_cidr           = "8.8.8.8/32"
  deployment_phase           = "services"
  verified_probe_instance_id = "i-0123456789abcdef0"
  bundle_commit              = "0123456789abcdef0123456789abcdef01234567"
  bundle_sha256              = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  dataset_release            = "rehearsal-v20"
  dataset_manifest_sha256    = "f6899fe0ece0f51a0616191d2d43a36d85b8337b5f8a225d62765e7e3ae32ddc"
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
  target          = module.security.aws_security_group.this["alb"]
  override_during = plan
  values          = { id = "sg-0123456789abcdef0" }
}

override_resource {
  target          = module.rds[0].aws_db_instance.this
  override_during = plan
  values = {
    id          = "airbob-lab-phase3-test"
    arn         = "arn:aws:rds:ap-northeast-2:942632789808:db:airbob-lab-phase3-test"
    address     = "airbob-lab-phase3-test.abcdefghijkl.ap-northeast-2.rds.amazonaws.com"
    port        = 3306
    resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
    master_user_secret = [{
      kms_key_id    = "arn:aws:kms:ap-northeast-2:942632789808:key/11111111-2222-3333-4444-555555555555"
      secret_arn    = "arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-test"
      secret_status = "active"
    }]
  }
}

override_resource {
  target          = aws_secretsmanager_secret.debezium[0]
  override_during = plan
  values = {
    arn = "arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:airbob/lab-phase3-test/debezium"
  }
}

override_data {
  target          = data.aws_s3_object.network_receipt[0]
  override_during = plan
  values = {
    body = jsonencode({
      schemaVersion       = 1, runId = "lab-phase3-test", vpcId = "vpc-0123456789abcdef0",
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
      schemaVersion   = 1, runId = "lab-phase3-test", vpcId = "vpc-0123456789abcdef0",
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
  target          = data.aws_s3_object.dataset_production_spec[0]
  override_during = plan
  values = {
    body = file("tests/fixtures/production-skew-v1.json")
  }
}

override_data {
  target          = data.aws_s3_object.data_bootstrap_receipt[0]
  override_during = plan
  values = {
    body = jsonencode({
      schemaVersion                  = 2
      runId                          = "lab-phase3-test"
      datasetRelease                 = "rehearsal-v20"
      datasetRunId                   = "20260816T001530Z-12345678"
      releaseKind                    = "pipeline-rehearsal"
      databaseBootstrap              = "dump"
      dumpSha256                     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
      flywayVersion                  = "27"
      migrationChecksumSha256        = "4444444444444444444444444444444444444444444444444444444444444444"
      schemaFingerprintSha256        = "5555555555555555555555555555555555555555555555555555555555555555"
      datasetManifestSha256          = "f6899fe0ece0f51a0616191d2d43a36d85b8337b5f8a225d62765e7e3ae32ddc"
      validatorSha256                = "7777777777777777777777777777777777777777777777777777777777777777"
      benchmarkDatasetManifestSha256 = "6666666666666666666666666666666666666666666666666666666666666666"
      calibrationSha256              = "8888888888888888888888888888888888888888888888888888888888888888"
      productionSpecSha256           = "bbba284a93ff00637928f5cfcf046cce1aab1f848bc31fd467f809d01d73fcdd"
      qualificationSha256            = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      databaseFingerprintSha256      = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
      restoreAttestationSha256       = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
      finalWorldFingerprintSha256    = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
      baseWorldFingerprintSha256     = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
      distributionFingerprintSha256  = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
      targetFingerprintSha256        = "0000000000000000000000000000000000000000000000000000000000000000"
      inventoryFingerprintSha256     = "1111111111111111111111111111111111111111111111111111111111111111"
      semanticAttestationSha256      = "2222222222222222222222222222222222222222222222222222222222222222"
      rdsResourceId                  = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
      rdsEngineVersion               = "8.0.40"
      outboxState                    = "empty"
      redisState                     = "empty"
      kafkaTopics                    = jsondecode(file("tests/fixtures/dataset-manifest.json")).kafka.topics
      connectorState                 = "RUNNING"
      searchState                    = "skipped"
      verifiedAt                     = "2030-01-01T00:30:00Z"
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
      module.rds[0].contract.configured_storage_gib == 20 &&
      module.rds[0].contract.storage_type == "gp3" &&
      module.rds[0].contract.iops == 3000 &&
      module.rds[0].contract.storage_throughput_mibps == 125 &&
      aws_secretsmanager_secret.debezium[0].recovery_window_in_days == 0 &&
      output.phase3_contract.database_bootstrap == "dump" &&
      output.phase3_contract.profile_version == "production-skew-v1" &&
      output.phase3_contract.production_spec_key == "benchmark/production-skew-v1.json" &&
      output.phase3_contract.rds_configured_storage_gib == 20 &&
      output.phase3_contract.release_kind == "pipeline-rehearsal" &&
      output.phase3_contract.search_enabled == false
    )
    error_message = "Dump mode must create the exact Single-AZ RDS and ordered secret-safe bootstrap contract."
  }

  assert {
    condition = alltrue([
      length(trimspace(aws_ssm_document.start_service[0].tags["Service"])) > 0,
      length(trimspace(aws_ssm_document.bootstrap_data[0].tags["Service"])) > 0,
      length(trimspace(aws_ssm_document.start_app[0].tags["Service"])) > 0,
    ])
    error_message = "Every lab-owned SSM document must carry a nonempty Service tag at creation time."
  }
}

run "accept_large_profile_with_large_spec_and_budgets" {
  command = plan

  variables {
    dataset_manifest_sha256 = "1eb29a5c8245bfaa435fa6c166e52ffb4c7c997410a873ecdba016e126107a0b"
  }

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        releaseTuple = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).releaseTuple, {
          profileVersion = "production-skew-large-v1"
          specSha256     = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        source = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).source, {
          productionSpecKey    = "benchmark/production-skew-large-v1.json"
          productionSpecSha256 = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        mysql = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql, {
          expectedTableRows = merge(
            jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql.expectedTableRows,
            {
              accommodation          = 200000
              member                 = 800000
              reservation            = 10000000
              review                 = 4000000
              wishlist               = 1600000
              wishlist_accommodation = 6000000
            },
          )
        })
      }))
    }
  }

  override_data {
    target          = data.aws_s3_object.dataset_production_spec[0]
    override_during = plan
    values = {
      body = file("tests/fixtures/production-skew-large-v1.json")
    }
  }

  assert {
    condition = (
      data.aws_s3_object.dataset_production_spec[0].key ==
      "datasets/rehearsal-v20/benchmark/production-skew-large-v1.json" &&
      module.rds[0].contract.instance_class == "db.t3.micro" &&
      module.rds[0].contract.configured_storage_gib == 100 &&
      module.rds[0].contract.storage_type == "gp3" &&
      module.rds[0].contract.iops == 3000 &&
      module.rds[0].contract.storage_throughput_mibps == 125 &&
      output.phase3_contract.profile_version == "production-skew-large-v1" &&
      output.phase3_contract.production_spec_key == "benchmark/production-skew-large-v1.json" &&
      output.phase3_contract.rds_configured_storage_gib == 100
    )
    error_message = "The large profile must select its immutable specification and 100-GiB gp3 dump capacity without changing instance class."
  }
}

run "reject_unsupported_third_profile" {
  command = plan

  variables {
    dataset_manifest_sha256 = "232fe77160e9e011ff9624c6320cf8285c04949218327fabb62c370bbe7a6a11"
  }

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        releaseTuple = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).releaseTuple, {
          profileVersion = "production-skew-v2"
        })
        source = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).source, {
          productionSpecKey = "benchmark/production-skew-v2.json"
        })
      }))
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "reject_large_profile_with_canonical_spec_key" {
  command = plan

  variables {
    dataset_manifest_sha256 = "7f0abfa75ad04e0db810583f72e88d3d7fe542c3dea683d75699e5941c375060"
  }

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        releaseTuple = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).releaseTuple, {
          profileVersion = "production-skew-large-v1"
          specSha256     = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        source = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).source, {
          productionSpecKey    = "benchmark/production-skew-v1.json"
          productionSpecSha256 = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        mysql = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql, {
          expectedTableRows = merge(
            jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql.expectedTableRows,
            {
              accommodation          = 200000
              member                 = 800000
              reservation            = 10000000
              review                 = 4000000
              wishlist               = 1600000
              wishlist_accommodation = 6000000
            },
          )
        })
      }))
    }
  }

  override_data {
    target          = data.aws_s3_object.dataset_production_spec[0]
    override_during = plan
    values = {
      body = file("tests/fixtures/production-skew-large-v1.json")
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "reject_large_profile_with_canonical_budget" {
  command = plan

  variables {
    dataset_manifest_sha256 = "8c53124766334722cb337c038e5e1e141b239e3547b3509865f8b5f7413067d2"
  }

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        releaseTuple = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).releaseTuple, {
          profileVersion = "production-skew-large-v1"
          specSha256     = "1e88a0d73b688479241895d807f7e24d456363668a4aa767c0140079fe699073"
        })
        source = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).source, {
          productionSpecKey    = "benchmark/production-skew-large-v1.json"
          productionSpecSha256 = "1e88a0d73b688479241895d807f7e24d456363668a4aa767c0140079fe699073"
        })
        mysql = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql, {
          expectedTableRows = merge(
            jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql.expectedTableRows,
            {
              accommodation          = 200000
              member                 = 800000
              reservation            = 10000000
              review                 = 4000000
              wishlist               = 1600000
              wishlist_accommodation = 6000000
            },
          )
        })
      }))
    }
  }

  override_data {
    target          = data.aws_s3_object.dataset_production_spec[0]
    override_during = plan
    values = {
      body = file("tests/fixtures/production-skew-large-mixed-budget.json")
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "reject_large_profile_with_underfilled_final_table" {
  command = plan

  variables {
    dataset_manifest_sha256 = "f67572e6fea04a9121f7520e50dc85f2d31932b5530fdcdf9138c3d71f2ea807"
  }

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        releaseTuple = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).releaseTuple, {
          profileVersion = "production-skew-large-v1"
          specSha256     = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        source = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).source, {
          productionSpecKey    = "benchmark/production-skew-large-v1.json"
          productionSpecSha256 = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        mysql = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql, {
          expectedTableRows = merge(
            jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql.expectedTableRows,
            {
              accommodation          = 200000
              member                 = 800000
              reservation            = 9999999
              review                 = 4000000
              wishlist               = 1600000
              wishlist_accommodation = 6000000
            },
          )
        })
      }))
    }
  }

  override_data {
    target          = data.aws_s3_object.dataset_production_spec[0]
    override_during = plan
    values = {
      body = file("tests/fixtures/production-skew-large-v1.json")
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "restore_rds_only_from_matching_snapshot" {
  command = plan

  variables {
    database_bootstrap              = "snapshot"
    rds_snapshot_identifier         = "airbob-dataset-rehearsal-v20"
    rds_snapshot_source_run_id      = "lab-phase3-test"
    rds_snapshot_source_resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
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
      allocated_storage      = 20
      storage_type           = "gp3"
      iops                   = 3000
      tags = {
        Project                        = "airbob"
        Environment                    = "performance-lab"
        Stack                          = "dataset"
        ManagedBy                      = "dataset-publisher"
        Persistence                    = "persistent"
        SourceLabRunId                 = "lab-phase3-test"
        SourceRdsResourceId            = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
        PromotionReceiptSchemaVersion  = "2"
        DataBootstrapKey               = "data-bootstrap/lab-phase3-test/rehearsal-v20.json"
        DataBootstrapVersionIdSha256   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        DataBootstrapSha256            = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        DirectReadinessKey             = "measurements/lab-phase3-test/direct-readiness.json"
        DirectReadinessVersionIdSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        DirectReadinessSha256          = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        DatasetRelease                 = "rehearsal-v20"
        DatasetRunId                   = "20260816T001530Z-12345678"
        DumpSha256                     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
        FlywayVersion                  = "27"
        ManifestSha256                 = "f6899fe0ece0f51a0616191d2d43a36d85b8337b5f8a225d62765e7e3ae32ddc"
      }
    }
  }

  assert {
    condition = (
      module.rds[0].contract.snapshot_identifier == "airbob-dataset-rehearsal-v20" &&
      module.rds[0].contract.configured_storage_gib == null &&
      module.rds[0].contract.storage_type == null &&
      module.rds[0].contract.iops == null &&
      module.rds[0].contract.storage_throughput_mibps == null &&
      output.phase3_contract.rds_configured_storage_gib == null &&
      output.phase3_contract.database_bootstrap == "snapshot"
    )
    error_message = "Snapshot mode must bind RDS creation to the prevalidated snapshot without overriding inherited storage."
  }
}

run "reject_snapshot_with_non_gp3_storage" {
  command = plan

  variables {
    database_bootstrap              = "snapshot"
    rds_snapshot_identifier         = "airbob-dataset-rehearsal-v20"
    rds_snapshot_source_run_id      = "lab-phase3-test"
    rds_snapshot_source_resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
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
      allocated_storage      = 20
      storage_type           = "gp2"
      iops                   = 3000
      tags = {
        Project                        = "airbob"
        Environment                    = "performance-lab"
        Stack                          = "dataset"
        ManagedBy                      = "dataset-publisher"
        Persistence                    = "persistent"
        SourceLabRunId                 = "lab-phase3-test"
        SourceRdsResourceId            = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
        PromotionReceiptSchemaVersion  = "2"
        DataBootstrapKey               = "data-bootstrap/lab-phase3-test/rehearsal-v20.json"
        DataBootstrapVersionIdSha256   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        DataBootstrapSha256            = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        DirectReadinessKey             = "measurements/lab-phase3-test/direct-readiness.json"
        DirectReadinessVersionIdSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        DirectReadinessSha256          = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        DatasetRelease                 = "rehearsal-v20"
        DatasetRunId                   = "20260816T001530Z-12345678"
        DumpSha256                     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
        FlywayVersion                  = "27"
        ManifestSha256                 = "f6899fe0ece0f51a0616191d2d43a36d85b8337b5f8a225d62765e7e3ae32ddc"
      }
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "reject_snapshot_above_baseline_iops" {
  command = plan

  variables {
    database_bootstrap              = "snapshot"
    rds_snapshot_identifier         = "airbob-dataset-rehearsal-v20"
    rds_snapshot_source_run_id      = "lab-phase3-test"
    rds_snapshot_source_resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
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
      allocated_storage      = 20
      storage_type           = "gp3"
      iops                   = 3001
      tags = {
        Project                        = "airbob"
        Environment                    = "performance-lab"
        Stack                          = "dataset"
        ManagedBy                      = "dataset-publisher"
        Persistence                    = "persistent"
        SourceLabRunId                 = "lab-phase3-test"
        SourceRdsResourceId            = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
        PromotionReceiptSchemaVersion  = "2"
        DataBootstrapKey               = "data-bootstrap/lab-phase3-test/rehearsal-v20.json"
        DataBootstrapVersionIdSha256   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        DataBootstrapSha256            = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        DirectReadinessKey             = "measurements/lab-phase3-test/direct-readiness.json"
        DirectReadinessVersionIdSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        DirectReadinessSha256          = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        DatasetRelease                 = "rehearsal-v20"
        DatasetRunId                   = "20260816T001530Z-12345678"
        DumpSha256                     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
        FlywayVersion                  = "27"
        ManifestSha256                 = "f6899fe0ece0f51a0616191d2d43a36d85b8337b5f8a225d62765e7e3ae32ddc"
      }
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "reject_snapshot_inputs_for_dump_bootstrap" {
  command = plan

  variables {
    rds_snapshot_identifier         = "airbob-dataset-rehearsal-v20"
    rds_snapshot_source_run_id      = "lab-phase3-test"
    rds_snapshot_source_resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
  }

  expect_failures = [
    var.rds_snapshot_identifier,
    var.rds_snapshot_source_run_id,
    var.rds_snapshot_source_resource_id,
  ]
}

run "reject_snapshot_bootstrap_without_source_identity" {
  command = plan

  variables {
    database_bootstrap      = "snapshot"
    rds_snapshot_identifier = "airbob-dataset-rehearsal-v20"
  }

  expect_failures = [
    var.rds_snapshot_source_run_id,
    var.rds_snapshot_source_resource_id,
  ]
}

run "reject_snapshot_with_different_source_identity" {
  command = plan

  variables {
    database_bootstrap              = "snapshot"
    rds_snapshot_identifier         = "airbob-dataset-rehearsal-v20"
    rds_snapshot_source_run_id      = "lab-different-source"
    rds_snapshot_source_resource_id = "db-ZYXWVUTSRQPONMLKJIHGFEDC"
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
      allocated_storage      = 20
      storage_type           = "gp3"
      iops                   = 3000
      tags = {
        Project                        = "airbob"
        Environment                    = "performance-lab"
        Stack                          = "dataset"
        ManagedBy                      = "dataset-publisher"
        Persistence                    = "persistent"
        SourceLabRunId                 = "lab-phase3-test"
        SourceRdsResourceId            = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
        PromotionReceiptSchemaVersion  = "2"
        DataBootstrapKey               = "data-bootstrap/lab-phase3-test/rehearsal-v20.json"
        DataBootstrapVersionIdSha256   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        DataBootstrapSha256            = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        DirectReadinessKey             = "measurements/lab-phase3-test/direct-readiness.json"
        DirectReadinessVersionIdSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        DirectReadinessSha256          = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        DatasetRelease                 = "rehearsal-v20"
        DatasetRunId                   = "20260816T001530Z-12345678"
        DumpSha256                     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
        FlywayVersion                  = "27"
        ManifestSha256                 = "f6899fe0ece0f51a0616191d2d43a36d85b8337b5f8a225d62765e7e3ae32ddc"
      }
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "reject_snapshot_outside_promotion_contract" {
  command = plan

  variables {
    database_bootstrap              = "snapshot"
    rds_snapshot_identifier         = "airbob-dataset-rehearsal-v20"
    rds_snapshot_source_run_id      = "lab-phase3-test"
    rds_snapshot_source_resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
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
      allocated_storage      = 20
      storage_type           = "gp3"
      iops                   = 3000
      tags = {
        DatasetRelease = "rehearsal-v20"
        DatasetRunId   = "20260816T001530Z-12345678"
        DumpSha256     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
        FlywayVersion  = "27"
        ManifestSha256 = "f6899fe0ece0f51a0616191d2d43a36d85b8337b5f8a225d62765e7e3ae32ddc"
      }
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "restore_large_profile_from_snapshot_with_sufficient_storage" {
  command = plan

  variables {
    database_bootstrap              = "snapshot"
    rds_snapshot_identifier         = "airbob-dataset-rehearsal-v20-large"
    rds_snapshot_source_run_id      = "lab-phase3-test"
    rds_snapshot_source_resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
    dataset_manifest_sha256         = "1eb29a5c8245bfaa435fa6c166e52ffb4c7c997410a873ecdba016e126107a0b"
  }

  override_data {
    target          = data.aws_ssm_parameter.foundation_contract
    override_during = plan
    values = {
      name = "/airbob/performance-lab/foundation/lab-contract"
      type = "String"
      value = jsonencode(merge(jsondecode(file("tests/fixtures/lab-contract.json")), {
        approved_rds_snapshot_identifier = "airbob-dataset-rehearsal-v20-large"
      }))
    }
  }

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        releaseTuple = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).releaseTuple, {
          profileVersion = "production-skew-large-v1"
          specSha256     = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        source = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).source, {
          productionSpecKey    = "benchmark/production-skew-large-v1.json"
          productionSpecSha256 = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        mysql = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql, {
          expectedTableRows = merge(
            jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql.expectedTableRows,
            {
              accommodation          = 200000
              member                 = 800000
              reservation            = 10000000
              review                 = 4000000
              wishlist               = 1600000
              wishlist_accommodation = 6000000
            },
          )
        })
      }))
    }
  }

  override_data {
    target          = data.aws_s3_object.dataset_production_spec[0]
    override_during = plan
    values = {
      body = file("tests/fixtures/production-skew-large-v1.json")
    }
  }

  override_data {
    target          = data.aws_db_snapshot.dataset[0]
    override_during = plan
    values = {
      db_snapshot_identifier = "airbob-dataset-rehearsal-v20-large"
      engine                 = "mysql"
      engine_version         = "8.0.40"
      status                 = "available"
      encrypted              = true
      allocated_storage      = 100
      storage_type           = "gp3"
      iops                   = 3000
      tags = {
        Project                        = "airbob"
        Environment                    = "performance-lab"
        Stack                          = "dataset"
        ManagedBy                      = "dataset-publisher"
        Persistence                    = "persistent"
        SourceLabRunId                 = "lab-phase3-test"
        SourceRdsResourceId            = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
        PromotionReceiptSchemaVersion  = "2"
        DataBootstrapKey               = "data-bootstrap/lab-phase3-test/rehearsal-v20.json"
        DataBootstrapVersionIdSha256   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        DataBootstrapSha256            = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        DirectReadinessKey             = "measurements/lab-phase3-test/direct-readiness.json"
        DirectReadinessVersionIdSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        DirectReadinessSha256          = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        DatasetRelease                 = "rehearsal-v20"
        DatasetRunId                   = "20260816T001530Z-12345678"
        DumpSha256                     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
        FlywayVersion                  = "27"
        ManifestSha256                 = "1eb29a5c8245bfaa435fa6c166e52ffb4c7c997410a873ecdba016e126107a0b"
      }
    }
  }

  assert {
    condition = (
      data.aws_db_snapshot.dataset[0].allocated_storage == 100 &&
      data.aws_db_snapshot.dataset[0].storage_type == "gp3" &&
      data.aws_db_snapshot.dataset[0].iops == 3000 &&
      module.rds[0].contract.snapshot_identifier == "airbob-dataset-rehearsal-v20-large" &&
      module.rds[0].contract.configured_storage_gib == null &&
      module.rds[0].contract.storage_type == null &&
      module.rds[0].contract.iops == null &&
      module.rds[0].contract.storage_throughput_mibps == null &&
      output.phase3_contract.profile_version == "production-skew-large-v1" &&
      output.phase3_contract.database_bootstrap == "snapshot"
    )
    error_message = "The large profile snapshot must inherit at least 100 GiB while preserving snapshot-backed RDS creation."
  }
}

run "reject_large_profile_snapshot_above_dataset_storage" {
  command = plan

  variables {
    database_bootstrap              = "snapshot"
    rds_snapshot_identifier         = "airbob-dataset-rehearsal-v20-large"
    rds_snapshot_source_run_id      = "lab-phase3-test"
    rds_snapshot_source_resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
    dataset_manifest_sha256         = "1eb29a5c8245bfaa435fa6c166e52ffb4c7c997410a873ecdba016e126107a0b"
  }


  override_data {
    target          = data.aws_ssm_parameter.foundation_contract
    override_during = plan
    values = {
      name = "/airbob/performance-lab/foundation/lab-contract"
      type = "String"
      value = jsonencode(merge(jsondecode(file("tests/fixtures/lab-contract.json")), {
        approved_rds_snapshot_identifier = "airbob-dataset-rehearsal-v20-large"
      }))
    }
  }

  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body = jsonencode(merge(jsondecode(file("tests/fixtures/dataset-manifest.json")), {
        releaseTuple = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).releaseTuple, {
          profileVersion = "production-skew-large-v1"
          specSha256     = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        source = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).source, {
          productionSpecKey    = "benchmark/production-skew-large-v1.json"
          productionSpecSha256 = "d32255ed92251be03f2eeeb81269ed5e9742baced384dfb3e47b5886bae9dc50"
        })
        mysql = merge(jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql, {
          expectedTableRows = merge(
            jsondecode(file("tests/fixtures/dataset-manifest.json")).mysql.expectedTableRows,
            {
              accommodation          = 200000
              member                 = 800000
              reservation            = 10000000
              review                 = 4000000
              wishlist               = 1600000
              wishlist_accommodation = 6000000
            },
          )
        })
      }))
    }
  }

  override_data {
    target          = data.aws_s3_object.dataset_production_spec[0]
    override_during = plan
    values = {
      body = file("tests/fixtures/production-skew-large-v1.json")
    }
  }

  override_data {
    target          = data.aws_db_snapshot.dataset[0]
    override_during = plan
    values = {
      db_snapshot_identifier = "airbob-dataset-rehearsal-v20-large"
      engine                 = "mysql"
      engine_version         = "8.0.40"
      status                 = "available"
      encrypted              = true
      allocated_storage      = 101
      storage_type           = "gp3"
      iops                   = 3000
      tags = {
        Project                        = "airbob"
        Environment                    = "performance-lab"
        Stack                          = "dataset"
        ManagedBy                      = "dataset-publisher"
        Persistence                    = "persistent"
        SourceLabRunId                 = "lab-phase3-test"
        SourceRdsResourceId            = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
        PromotionReceiptSchemaVersion  = "2"
        DataBootstrapKey               = "data-bootstrap/lab-phase3-test/rehearsal-v20.json"
        DataBootstrapVersionIdSha256   = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        DataBootstrapSha256            = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        DirectReadinessKey             = "measurements/lab-phase3-test/direct-readiness.json"
        DirectReadinessVersionIdSha256 = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        DirectReadinessSha256          = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        DatasetRelease                 = "rehearsal-v20"
        DatasetRunId                   = "20260816T001530Z-12345678"
        DumpSha256                     = "94094053eaad6446274f30cbdd71c28e23a578d27dc68e26c8f9f051477a0fc2"
        FlywayVersion                  = "27"
        ManifestSha256                 = "1eb29a5c8245bfaa435fa6c166e52ffb4c7c997410a873ecdba016e126107a0b"
      }
    }
  }

  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "attest_only_the_exact_ordered_data_receipt" {
  command = plan

  variables {
    deployment_phase = "data-ready"
  }

  assert {
    condition = (
      output.phase3_contract.data_ready == true &&
      output.phase3_contract.data_bootstrap_receipt_key == "data-bootstrap/lab-phase3-test/rehearsal-v20.json"
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
    database_bootstrap              = "snapshot"
    rds_snapshot_identifier         = "airbob-dataset-invalid--snapshot"
    rds_snapshot_source_run_id      = "lab-phase3-test"
    rds_snapshot_source_resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
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
      output.phase4_contract.alb_security_group_id == "sg-0123456789abcdef0" &&
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
      alltrue([
        for role in values(aws_iam_role.host) :
        role.permissions_boundary == "arn:aws:iam::942632789808:policy/airbob-performance-lab-host-boundary"
      ]) &&
      toset(keys(aws_iam_role_policy.measurement_data_plane)) == toset(["debezium", "loadgen", "monitoring"]) &&
      alltrue([
        for role in ["debezium", "loadgen"] :
        contains(one([
          for statement in jsondecode(aws_iam_role_policy.measurement_data_plane[role].policy).Statement : statement
          if statement.Sid == "ReadMeasurementInputs"
        ]).Resource, "arn:aws:s3:::airbob-performance-lab-evidence-942632789808/measurement-inputs/lab-phase3-test/*")
      ]) &&
      length([
        for statement in jsondecode(aws_iam_role_policy.measurement_data_plane["monitoring"].policy).Statement : statement
        if statement.Sid == "ReadMeasurementInputs"
      ]) == 0 &&
      toset(one([
        for statement in jsondecode(aws_iam_role_policy.measurement_data_plane["loadgen"].policy).Statement : statement
        if statement.Sid == "ReadSelectedBenchmarkManifest"
        ]).Resource) == toset([
        "arn:aws:s3:::airbob-performance-lab-dataset-942632789808/datasets/rehearsal-v20/benchmark/manifest.json",
        "arn:aws:s3:::airbob-performance-lab-dataset-942632789808/datasets/rehearsal-v20/benchmark/dataset-manifest.json",
      ]) &&
      alltrue([
        for policy in values(aws_iam_role_policy.measurement_data_plane) :
        toset(one([
          for statement in jsondecode(policy.policy).Statement : statement
          if statement.Sid == "WriteMeasurementEvidence"
        ]).Action) == toset(["s3:PutObject", "s3:PutObjectTagging"]) &&
        one([
          for statement in jsondecode(policy.policy).Statement : statement
          if statement.Sid == "WriteMeasurementEvidence"
        ]).Resource == "arn:aws:s3:::airbob-performance-lab-evidence-942632789808/measurements/lab-phase3-test/*" &&
        toset(one([
          for statement in jsondecode(policy.policy).Statement : statement
          if statement.Sid == "WriteMeasurementEvidence"
        ]).Condition.StringEquals["s3:RequestObjectTag/Retention"]) == toset(["raw", "summary"])
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
