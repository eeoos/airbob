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
}

run "validate_zero_resource_boundary" {
  command = plan

  assert {
    condition = (
      output.state_boundaries.bootstrap == "airbob/bootstrap/terraform.tfstate" &&
      output.state_boundaries.foundation == "airbob/foundation/terraform.tfstate" &&
      output.state_boundaries.dns == "airbob/dns/terraform.tfstate" &&
      output.state_boundaries.lab == "airbob/lab/terraform.tfstate" &&
      output.persistent_resource_contract.api_fqdn == "api.airbob.cloud" &&
      length(output.persistent_resource_contract.ecr_repositories) == 10
    )
    error_message = "The empty lab state must validate the exact persistent contract without creating resources."
  }
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

  expect_failures = [output.persistent_resource_contract]
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

  expect_failures = [output.persistent_resource_contract]
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

  expect_failures = [output.persistent_resource_contract]
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

  expect_failures = [output.persistent_resource_contract]
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
