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
  run_id           = "lab-contract-test"
  expires_at       = "1893456000"
  fencing_token    = 42
  ami_id           = "ami-0123456789abcdef0"
  dns_mode         = "direct-only"
  alb_ingress_cidr = "8.8.8.8/32"
}

run "validate_persistent_boundary_with_network_phase" {
  command = plan

  assert {
    condition = (
      output.state_boundaries.bootstrap == "airbob/bootstrap/terraform.tfstate" &&
      output.state_boundaries.foundation == "airbob/foundation/terraform.tfstate" &&
      output.state_boundaries.dns == "airbob/dns/terraform.tfstate" &&
      output.state_boundaries.lab == "airbob/lab/terraform.tfstate" &&
      terraform_data.run_identity.input.run_id == "lab-contract-test" &&
      terraform_data.run_identity.input.resource_fencing_token == 42 &&
      output.persistent_resource_contract.api_fqdn == "api.airbob.cloud" &&
      length(output.persistent_resource_contract.ecr_repositories) == 10 &&
      module.security.alb_https_ingress_cidr == "8.8.8.8/32"
    )
    error_message = "The lab state must bind its run/fence identity, exact persistent contract, and direct-only ALB /32 ingress."
  }
}

run "accept_cutover_with_public_alb_ingress" {
  command = plan

  variables {
    dns_mode         = "cutover"
    alb_ingress_cidr = "0.0.0.0/0"
  }

  assert {
    condition     = module.security.alb_https_ingress_cidr == "0.0.0.0/0"
    error_message = "Explicit cutover mode must retain public ALB HTTPS ingress."
  }
}

run "reject_direct_only_public_alb_ingress" {
  command = plan

  variables {
    dns_mode         = "direct-only"
    alb_ingress_cidr = "0.0.0.0/0"
  }

  expect_failures = [var.alb_ingress_cidr]
}

run "reject_direct_only_private_alb_ingress" {
  command = plan

  variables {
    dns_mode         = "direct-only"
    alb_ingress_cidr = "10.42.0.1/32"
  }

  expect_failures = [var.alb_ingress_cidr]
}

run "reject_direct_only_non_host_alb_ingress" {
  command = plan

  variables {
    dns_mode         = "direct-only"
    alb_ingress_cidr = "8.8.8.8/31"
  }

  expect_failures = [var.alb_ingress_cidr]
}

run "reject_cutover_restricted_alb_ingress" {
  command = plan

  variables {
    dns_mode         = "cutover"
    alb_ingress_cidr = "8.8.8.8/32"
  }

  expect_failures = [var.alb_ingress_cidr]
}

run "reject_unknown_dns_mode" {
  command = plan

  variables {
    dns_mode = "disabled"
  }

  expect_failures = [var.dns_mode]
}

run "reject_unsupported_contract_schema" {
  command = plan

  override_data {
    target = data.aws_ssm_parameter.foundation_contract
    values = {
      value = jsonencode(merge(
        jsondecode(file("tests/fixtures/lab-contract.json")),
        { schemaVersion = 2 },
      ))
    }
  }

  expect_failures = [check.foundation_boundary, output.persistent_resource_contract]
}

run "reject_extra_contract_field" {
  command = plan

  override_data {
    target = data.aws_ssm_parameter.foundation_contract
    values = {
      value = jsonencode(merge(
        jsondecode(file("tests/fixtures/lab-contract.json")),
        { secret = "must-not-cross-boundary" },
      ))
    }
  }

  expect_failures = [check.foundation_boundary, output.persistent_resource_contract]
}

run "reject_substituted_dataset_bucket" {
  command = plan

  override_data {
    target = data.aws_ssm_parameter.foundation_contract
    values = {
      value = jsonencode(merge(
        jsondecode(file("tests/fixtures/lab-contract.json")),
        { dataset_bucket_name = "attacker-controlled-bucket" },
      ))
    }
  }

  expect_failures = [check.foundation_boundary, output.persistent_resource_contract]
}

run "reject_mismatched_ecr_pair" {
  command = plan

  override_data {
    target = data.aws_ssm_parameter.foundation_contract
    values = {
      value = jsonencode(merge(
        jsondecode(file("tests/fixtures/lab-contract.json")),
        {
          ecr_repositories = merge(
            jsondecode(file("tests/fixtures/lab-contract.json")).ecr_repositories,
            {
              REDIS_IMAGE = merge(
                jsondecode(file("tests/fixtures/lab-contract.json")).ecr_repositories.REDIS_IMAGE,
                { url = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/redis-exporter" },
              )
            },
          )
        },
      ))
    }
  }

  expect_failures = [check.foundation_boundary, output.persistent_resource_contract]
}

run "reject_wrong_account" {
  command = plan

  variables {
    account_id = "111111111111"
  }

  expect_failures = [var.account_id]
}

run "reject_wrong_region" {
  command = plan

  variables {
    aws_region = "us-east-1"
  }

  expect_failures = [var.aws_region]
}

run "reject_rds_unsafe_run_id" {
  command = plan

  variables {
    run_id = "invalid--run"
  }

  expect_failures = [var.run_id]
}

run "reject_missing_or_invalid_fencing_token" {
  command = plan

  variables {
    fencing_token = 0
  }

  expect_failures = [var.fencing_token]
}
