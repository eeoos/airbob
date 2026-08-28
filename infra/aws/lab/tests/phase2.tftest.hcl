mock_provider "aws" {
  override_data {
    target          = data.aws_caller_identity.current
    override_during = plan
    values = {
      account_id = "942632789808"
      arn        = "arn:aws:iam::942632789808:role/lab-test"
      user_id    = "AROATEST"
    }
  }

  override_data {
    target          = data.aws_region.current
    override_during = plan
    values = {
      region = "ap-northeast-2"
    }
  }

  override_data {
    target          = data.aws_ssm_parameter.foundation_contract
    override_during = plan
    values = {
      name  = "/airbob/performance-lab/foundation/lab-contract"
      type  = "String"
      value = file("tests/fixtures/lab-contract.json")
    }
  }

  override_data {
    target          = data.aws_ami.selected
    override_during = plan
    values = {
      id           = "ami-0123456789abcdef0"
      architecture = "x86_64"
      owner_id     = "137112412989"
      state        = "available"
    }
  }
}

variables {
  run_id        = "phase2-test"
  expires_at    = "1893456000"
  fencing_token = 42
  ami_id        = "ami-0123456789abcdef0"
}

run "network_creates_probe_without_services" {
  command = plan

  assert {
    condition = (
      output.phase2_contract.deployment_phase == "network" &&
      output.phase2_contract.probe_enabled &&
      length(output.phase2_contract.services) == 0 &&
      output.phase2_contract.redis_topology.host_count == 1 &&
      output.phase2_contract.redis_topology.redis_processes == 2 &&
      output.phase2_contract.redis_topology.exporters == 2 &&
      output.phase2_contract.instance_types.redis == "t3.small" &&
      output.phase2_contract.instance_types.kafka == "t3.medium" &&
      output.phase2_contract.instance_types.debezium == "t3.medium" &&
      output.phase2_contract.instance_types.elasticsearch == "t3.medium" &&
      output.phase2_contract.instance_types.monitoring == "t3.small"
    )
    error_message = "The network phase must create only the disposable probe and fixed Phase 2 topology contract."
  }

  assert {
    condition = (
      length(aws_iam_role_policy.data_plane) == 0 &&
      jsondecode(aws_iam_role_policy.probe_egress[0].policy).Statement[0].Action == "s3:GetBucketLocation" &&
      jsondecode(aws_iam_role_policy.probe_egress[0].policy).Statement[0].Resource == "arn:aws:s3:::airbob-performance-lab-evidence-942632789808"
    )
    error_message = "Network validation resources must retain the exact run tags and probe-only signed S3 permission."
  }
}

run "reject_services_without_probe_identity" {
  command = plan

  variables {
    deployment_phase = "services"
  }

  expect_failures = [
    var.verified_probe_instance_id,
    var.bundle_commit,
    var.bundle_sha256,
    var.dataset_release,
    var.dataset_manifest_sha256,
    var.database_bootstrap,
    var.rds_engine_version,
    check.app_release,
  ]
}

run "reject_unapproved_image_reference_set" {
  command = plan

  variables {
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
      REDIS_IMAGE = "public.example/redis@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
  }

  override_data {
    target = data.aws_s3_object.network_receipt[0]
    values = {
      body = "{}"
    }
  }

  override_data {
    target = data.aws_s3_object.probe_clearance_receipt[0]
    values = {
      body = "{}"
    }
  }

  override_data {
    target = data.aws_s3_object.bundle_manifest[0]
    values = {
      body = "{}"
    }
  }

  override_data {
    target = data.aws_s3_object.bundle_checksum[0]
    values = {
      body = ""
    }
  }

  override_data {
    target = data.aws_s3_object.dataset_manifest[0]
    values = {
      body = "{}"
    }
  }

  expect_failures = [
    check.phase_transition,
    check.service_release,
    check.dataset_release,
    terraform_data.network_receipt_gate,
    terraform_data.probe_clearance_gate,
    terraform_data.service_release_gate,
    terraform_data.dataset_release_gate,
  ]
}

run "services_require_both_receipts_and_immutable_release" {
  command = plan

  variables {
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
    target          = module.rds[0].aws_db_instance.this
    override_during = plan
    values = {
      address     = "airbob-phase2-test.abcdefghijkl.ap-northeast-2.rds.amazonaws.com"
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
      arn = "arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:airbob/phase2-test/debezium"
    }
  }

  override_resource {
    target          = module.network.aws_vpc.this
    override_during = plan
    values = {
      id = "vpc-0123456789abcdef0"
    }
  }

  override_resource {
    target          = module.network.aws_route_table.private["primary"]
    override_during = plan
    values = {
      id = "rtb-0123456789abcdef0"
    }
  }

  override_data {
    target          = data.aws_s3_object.network_receipt[0]
    override_during = plan
    values = {
      body = jsonencode({
        schemaVersion       = 1
        runId               = "phase2-test"
        vpcId               = "vpc-0123456789abcdef0"
        primaryRouteTableId = "rtb-0123456789abcdef0"
        probeInstanceId     = "i-0123456789abcdef0"
        amiId               = "ami-0123456789abcdef0"
        s3Gateway           = "verified"
        ecrApi              = "verified"
        ssmApi              = "verified"
        secretsManagerApi   = "verified"
        verifiedAt          = "2030-01-01T00:00:00Z"
      })
    }
  }

  override_data {
    target          = data.aws_s3_object.probe_clearance_receipt[0]
    override_during = plan
    values = {
      body = jsonencode({
        schemaVersion   = 1
        runId           = "phase2-test"
        vpcId           = "vpc-0123456789abcdef0"
        probeInstanceId = "i-0123456789abcdef0"
        instanceState   = "terminated"
        clearedAt       = "2030-01-01T00:05:00Z"
      })
    }
  }

  override_data {
    target          = data.aws_s3_object.bundle_manifest[0]
    override_during = plan
    values = {
      body = jsonencode({
        schemaVersion = 1
        commit        = "0123456789abcdef0123456789abcdef01234567"
        archive       = "airbob-service-bundles-0123456789abcdef0123456789abcdef01234567.tar.gz"
        sha256        = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
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

  assert {
    condition = (
      output.phase2_contract.deployment_phase == "services" &&
      !output.phase2_contract.probe_enabled &&
      length(aws_iam_role_policy.data_plane) == 5 &&
      length(output.phase2_contract.services) == 5 &&
      output.phase2_contract.redis_topology.general == "redis-general.lab.airbob.internal:6379" &&
      output.phase2_contract.redis_topology.cache == "redis-cache.lab.airbob.internal:6380" &&
      output.phase2_contract.private_dns_zone_id == "Z0987654321PRIVATE"
    )
    error_message = "The services phase must create exactly five dependency hosts after both probe receipts validate."
  }
}
