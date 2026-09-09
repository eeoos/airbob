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
  dataset_release            = "korea-growth-v3-64deb4f838935b8e-aws"
  dataset_manifest_sha256    = filesha256("tests/fixtures/growth-aws-manifest.json")
  data_qualification_only    = true
  database_bootstrap         = "dump"
  rds_engine_version         = "8.4.11"
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
    resource_id = "db-ABCDEFGHIJKLMNOPQRSTUVWXYZ"
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
    body = file("tests/fixtures/growth-aws-manifest.json")
  }
}

run "qualify_growth_without_application_or_v2_spec" {
  command = plan
  assert {
    condition = (
      local.dataset_release_valid &&
      length(module.alb) == 0 && length(module.app_asg) == 0 &&
      length(data.aws_s3_object.dataset_production_spec) == 0 &&
      module.rds[0].contract.engine_version == "8.4.11" &&
      strcontains(local.bootstrap_data_command, "bootstrap-growth-aws.py") &&
      length(local.bootstrap_data_command) < 45000
    )
    error_message = "Growth preparation must select its bounded helper and exact MySQL engine without v2 spec or app resources."
  }
}

run "reject_growth_application_launch_until_runtime_contract_is_connected" {
  command = plan
  variables { data_qualification_only = false }
  expect_failures = [terraform_data.dataset_release_gate, check.dataset_release]
}

run "reject_growth_engine_drift_before_bootstrap" {
  command = plan
  variables { rds_engine_version = "8.4.8" }
  expect_failures = [terraform_data.dataset_release_gate, check.dataset_release]
}

run "qualify_growth_with_bounded_private_app_probe" {
  command = plan
  variables {
    growth_app_read_qualification = true
    growth_app_commit             = "63a343fa2d3aaf4d78c0ddd436cd84a7d0633d6d"
    growth_app_jar_sha256         = "d0f9cb6eb49351fd51b608fedf1bdb9a210472dc35093aa84e7c611ca7c8ee3a"
  }
  assert {
    condition     = length(module.app_asg) == 0 && length(module.alb) == 0 && strcontains(local.growth_bootstrap_data_command, "AIRBOB_GROWTH_APP_READ_QUALIFICATION='true'")
    error_message = "The app probe must remain a private preparation without ALB or ASG capacity."
  }
  assert {
    condition     = length(local.growth_bootstrap_data_command) < 45000
    error_message = "Compressed helpers must fit the SSM command bound."
  }
}

run "reject_app_probe_without_qualified_jar_identity" {
  command = plan
  variables {
    growth_app_read_qualification = true
    growth_app_commit             = "63a343fa2d3aaf4d78c0ddd436cd84a7d0633d6d"
  }
  expect_failures = [var.growth_app_read_qualification]
}
