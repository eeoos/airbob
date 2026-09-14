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
  run_id                       = "lab-mac-import-test"
  expires_at                   = "1893456000"
  fencing_token                = 42
  ami_id                       = "ami-0123456789abcdef0"
  dns_mode                     = "direct-only"
  alb_ingress_cidr             = "8.8.8.8/32"
  deployment_phase             = "services"
  verified_probe_instance_id   = "i-0123456789abcdef0"
  bundle_commit                = "0123456789abcdef0123456789abcdef01234567"
  bundle_sha256                = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  dataset_release              = "global-growth-b-aaaaaaaaaaaaaaaa"
  dataset_manifest_sha256      = "f6899fe0ece0f51a0616191d2d43a36d85b8337b5f8a225d62765e7e3ae32ddc"
  database_bootstrap           = "dump"
  rds_engine_version           = "8.4.11"
  rds_instance_class           = "db.m6i.large"
  global_b_prepare_only        = true
  global_b_import_from_mac     = true
  global_b_manifest_version_id = "manifest-version-1"
  global_b_lease_owner         = "controller-mac-import"
  app_image_reference          = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-repo@sha256:9123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
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
    id          = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
    identifier  = "airbob-lab-mac-import-test"
    arn         = "arn:aws:rds:ap-northeast-2:942632789808:db:airbob-lab-mac-import-test"
    address     = "airbob-lab-mac-import-test.abcdefghijkl.ap-northeast-2.rds.amazonaws.com"
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
    arn = "arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:airbob/lab-mac-import-test/debezium"
  }
}

override_data {
  target          = data.aws_s3_object.network_receipt[0]
  override_during = plan
  values = {
    body = jsonencode({
      schemaVersion       = 1, runId = "lab-mac-import-test", vpcId = "vpc-0123456789abcdef0",
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
      schemaVersion   = 1, runId = "lab-mac-import-test", vpcId = "vpc-0123456789abcdef0",
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

override_resource {
  target          = module.nat.aws_instance.this
  override_during = plan
  values          = { id = "i-11111111111111111" }
}

override_resource {
  target          = module.security.aws_security_group.this["nat"]
  override_during = plan
  values          = { id = "sg-11111111111111111" }
}

override_resource {
  target          = module.security.aws_security_group.this["rds"]
  override_during = plan
  values          = { id = "sg-22222222222222222" }
}

run "mac_import_uses_existing_nat_and_one_rds" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { toolSources = { for name in keys(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).toolSources) : name => filesha256("../scripts/${name}") } })))
  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { toolSources = { for name in keys(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).toolSources) : name => filesha256("../scripts/${name}") } })))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  assert {
    condition = (
      length(module.service_hosts.instance_ids) == 0 &&
      length(module.egress_probe.instance_ids) == 0 &&
      length(aws_iam_role_policy.data_bootstrap) == 0 &&
      length(aws_iam_role_policy.growth_b_preparation_inputs) == 0 &&
      toset(keys(aws_iam_role.host)) == toset(["nat", "probe"]) &&
      length(aws_ssm_document.bootstrap_data) == 0 &&
      length(aws_ssm_association.data_bootstrap) == 0 &&
      length(aws_route53_record.private_service) == 0 &&
      length(module.app_asg) == 0 && length(module.alb) == 0 &&
      local.growth_b_context == null && local.bootstrap_data_command == ""
    )
    error_message = "Mac import must omit every preparation host, host policy/DNS and bootstrap command while keeping the normal NAT identity."
  }
  assert {
    condition = (
      length(module.rds) == 1 &&
      module.rds[0].identifier == "airbob-lab-mac-import-test" &&
      module.rds[0].contract.instance_class == "db.m6i.large" &&
      module.rds[0].contract.configured_storage_gib == 100 &&
      module.rds[0].contract.storage_encrypted && !module.rds[0].contract.multi_az &&
      module.rds[0].contract.manage_master_user_password &&
      module.rds[0].contract.storage_type == "gp3" &&
      terraform_data.run_identity.input.resource_fencing_token == 42 &&
      local.ephemeral_tags.ExpiresAt == "1893456000" &&
      output.global_b_preparation.host_instance_id == null &&
      !output.global_b_preparation.deployment_ready && output.global_b_preparation.completion_requires_receipt
    )
    error_message = "Mac import must retain the single encrypted 100GiB RDS, run/fence/expiry and incomplete preparation status."
  }
  assert {
    condition = (
      length(aws_vpc_security_group_ingress_rule.mac_import_rds) == 1 &&
      aws_vpc_security_group_ingress_rule.mac_import_rds[0].security_group_id == "sg-22222222222222222" &&
      aws_vpc_security_group_ingress_rule.mac_import_rds[0].referenced_security_group_id == "sg-11111111111111111" &&
      aws_vpc_security_group_ingress_rule.mac_import_rds[0].ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.mac_import_rds[0].from_port == 3306 &&
      aws_vpc_security_group_ingress_rule.mac_import_rds[0].to_port == 3306 &&
      aws_vpc_security_group_ingress_rule.mac_import_rds[0].cidr_ipv4 == null &&
      aws_vpc_security_group_ingress_rule.mac_import_rds[0].cidr_ipv6 == null
    )
    error_message = "The only Mac ingress rule must reference the existing NAT security group on MySQL port 3306, with no public CIDR."
  }
  assert {
    condition = output.global_b_mac_import == {
      selected              = true
      nat_instance_id       = "i-11111111111111111"
      rds_instance_id       = "airbob-lab-mac-import-test"
      rds_resource_id       = "db-ABCDEFGHIJKLMNOPQRSTUVWX"
      rds_endpoint          = "airbob-lab-mac-import-test.abcdefghijkl.ap-northeast-2.rds.amazonaws.com"
      rds_master_secret_arn = "arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-test"
    }
    error_message = "The Mac output must contain only the selected flag and exact existing NAT/RDS non-secret coordinates."
  }
}

run "mac_accepts_pinned_historical_linux_sources" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  assert {
    condition     = local.growth_b_dataset_valid && local.dataset_manifest.toolSources != local.growth_b_helper_sources && local.growth_b_context == null && local.bootstrap_data_command == ""
    error_message = "Historical Linux source hashes are sealed provenance only in Mac mode; no Linux host tool may execute."
  }
}

run "normal_host_import_retains_exact_toolchain_and_bootstrap" {
  command = plan
  variables {
    dataset_manifest_sha256  = sha256(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { toolSources = { for name in keys(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).toolSources) : name => filesha256("../scripts/${name}") } })))
    global_b_import_from_mac = false
  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { toolSources = { for name in keys(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).toolSources) : name => filesha256("../scripts/${name}") } })))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  assert {
    condition = (
      toset(keys(module.service_hosts.instance_ids)) == toset(["debezium"]) &&
      length(aws_iam_role_policy.data_bootstrap) == 1 && length(aws_iam_role_policy.growth_b_preparation_inputs) == 1 &&
      length(aws_ssm_document.bootstrap_data) == 1 && length(aws_ssm_association.data_bootstrap) == 1 &&
      toset(keys(aws_route53_record.private_service)) == toset(["connect.lab.airbob.internal"]) &&
      length(aws_vpc_security_group_ingress_rule.mac_import_rds) == 0 && output.global_b_mac_import == null &&
      local.growth_b_context.toolSources == local.dataset_manifest.toolSources &&
      strcontains(local.bootstrap_data_command, "/opt/airbob/bootstrap-helpers/bootstrap-growth-b-entry.sh") &&
      !output.global_b_preparation.deployment_ready
    )
    error_message = "Normal host import must retain the sole Debezium preparation EC2 and exact Linux bootstrap, without Mac ingress."
  }
}

run "mac_small_class_retains_same_rds_address_and_storage" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))
    rds_instance_class      = "db.t3.small"
  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  assert {
    condition = (
      length(module.rds) == 1 && module.rds[0].identifier == "airbob-lab-mac-import-test" &&
      module.rds[0].contract.instance_class == "db.t3.small" && module.rds[0].contract.configured_storage_gib == 100 &&
      module.rds[0].contract.storage_encrypted && !module.rds[0].contract.multi_az &&
      output.global_b_mac_import.rds_resource_id == "db-ABCDEFGHIJKLMNOPQRSTUVWX" &&
      length(module.service_hosts.instance_ids) == 0 && local.ephemeral_tags.ExpiresAt == "1893456000"
    )
    error_message = "The small class must use the same module.rds[0] identity, encrypted 100GiB storage and original TTL."
  }
}

run "host_rejects_historical_linux_sources" {
  command = plan
  variables {
    dataset_manifest_sha256  = sha256(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))
    global_b_import_from_mac = false
  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "mac_rejects_changed_manifest_bytes" {
  command = plan
  variables {
    dataset_manifest_sha256 = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "mac_requests_exact_selected_manifest_and_envelope_versions" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  assert {
    condition = (
      data.aws_s3_object.dataset_manifest[0].version_id == "manifest-version-1" &&
      data.aws_s3_object.growth_b_envelope[0].version_id == "version-envelope" &&
      data.aws_s3_object.dataset_manifest[0].key == "datasets/global-growth-b-aaaaaaaaaaaaaaaa-aws-preparation/aws-preparation-${var.dataset_manifest_sha256}.json" &&
      data.aws_s3_object.growth_b_envelope[0].key == local.dataset_manifest.files.envelope.key
    )
    error_message = "Mac mode must still request exact manifest and envelope S3 versions and SHA-addressed keys."
  }
}

run "mac_rejects_changed_envelope_bytes" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json"))))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/envelope.json")), { account = "111111111111" })))
      version_id  = "version-envelope"
    }
  }
  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "mac_rejects_wrong_dataset" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { datasetId = "global-growth-b-bbbbbbbbbbbbbbbb" })))

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { datasetId = "global-growth-b-bbbbbbbbbbbbbbbb" })))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "mac_rejects_wrong_engine" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { mysql = { version = "8.0.40", flywayVersion = 28 } })))

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { mysql = { version = "8.0.40", flywayVersion = 28 } })))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "mac_rejects_wrong_storage" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { storage = merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).storage, { rdsAllocatedGiB = 99 }) })))

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { storage = merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).storage, { rdsAllocatedGiB = 99 }) })))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "mac_rejects_malformed_historical_source" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { toolSources = merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).toolSources, { "growth_b_prepare.py" = "not-a-sha256" }) })))

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { toolSources = merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).toolSources, { "growth_b_prepare.py" = "not-a-sha256" }) })))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "mac_rejects_extra_historical_source" {
  command = plan
  variables {
    dataset_manifest_sha256 = sha256(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { toolSources = merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).toolSources, { "unreviewed.py" = sha256("unreviewed") }) })))

  }
  override_data {
    target          = data.aws_s3_object.dataset_manifest[0]
    override_during = plan
    values = {
      body_base64 = base64encode(jsonencode(merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")), { toolSources = merge(jsondecode(file("tests/fixtures/global-b-prepare/manifest.json")).toolSources, { "unreviewed.py" = sha256("unreviewed") }) })))
      version_id  = "manifest-version-1"
    }
  }
  override_data {
    target          = data.aws_s3_object.growth_b_envelope[0]
    override_during = plan
    values = {
      body_base64 = base64encode(file("tests/fixtures/global-b-prepare/envelope.json"))
      version_id  = "version-envelope"
    }
  }
  expect_failures = [check.dataset_release, terraform_data.dataset_release_gate]
}

run "mac_requires_explicit_preparation_mode" {
  command = plan
  variables {
    deployment_phase      = "network"
    global_b_prepare_only = false
    rds_instance_class    = "db.t3.small"
    rds_engine_version    = "8.0.40"
    dataset_release       = "rehearsal-v20"
  }
  expect_failures = [var.global_b_import_from_mac]
}
