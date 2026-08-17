mock_provider "aws" {
  override_data {
    target          = data.aws_caller_identity.current
    override_during = plan
    values = {
      account_id = "942632789808"
      arn        = "arn:aws:iam::942632789808:role/dns-test"
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
      name = "/airbob/performance-lab/foundation/dns-contract"
      type = "String"
      value = jsonencode({
        schemaVersion = 1
        zone_id       = "Z0123456789EXAMPLE"
        zone_name     = "airbob.cloud"
        api_fqdn      = "api.airbob.cloud"
      })
    }
  }

  override_data {
    target          = data.aws_lb.api
    override_during = plan
    values = {
      arn                = "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-api/50dc6c495c0c9188"
      dns_name           = "airbob-api-123456789.ap-northeast-2.elb.amazonaws.com"
      zone_id            = "ZWKZPGTI48KDX"
      internal           = false
      load_balancer_type = "application"
      tags = {
        Project      = "airbob"
        Environment  = "performance-lab"
        Stack        = "lab"
        ManagedBy    = "terraform"
        Persistence  = "ephemeral"
        RunId        = "lab-dns-test"
        FencingToken = "42"
      }
    }
  }
}

variables {
  oci_origin_ipv4 = "140.245.76.140"
  run_id          = "lab-dns-test"
  fencing_token   = 42
}

run "oci_only_safe_default" {
  command = plan

  assert {
    condition = (
      aws_route53_record.oci_api.zone_id == "Z0123456789EXAMPLE" &&
      aws_route53_record.oci_api.name == "api.airbob.cloud" &&
      aws_route53_record.oci_api.type == "A" &&
      aws_route53_record.oci_api.ttl == 60 &&
      one(aws_route53_record.oci_api.records) == "140.245.76.140" &&
      aws_route53_record.oci_api.set_identifier == "oci" &&
      one(aws_route53_record.oci_api.weighted_routing_policy).weight == 100 &&
      length(aws_route53_record.aws_api) == 0
    )
    error_message = "The DNS default must contain only the OCI origin at weight 100 and TTL 60."
  }
}

run "stage_aws_alias_at_zero" {
  command = plan

  variables {
    aws_alb_arn = "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-api/50dc6c495c0c9188"
  }

  assert {
    condition = (
      length(aws_route53_record.aws_api) == 1 &&
      aws_route53_record.aws_api[0].set_identifier == "aws" &&
      one(aws_route53_record.aws_api[0].weighted_routing_policy).weight == 0 &&
      one(aws_route53_record.aws_api[0].alias).name == "airbob-api-123456789.ap-northeast-2.elb.amazonaws.com" &&
      one(aws_route53_record.aws_api[0].alias).zone_id == "ZWKZPGTI48KDX" &&
      one(aws_route53_record.aws_api[0].alias).evaluate_target_health
    )
    error_message = "The optional AWS origin must be an ALB alias staged at weight zero."
  }
}

run "switch_all_weight_to_aws" {
  command = plan

  variables {
    aws_alb_arn    = "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-api/50dc6c495c0c9188"
    traffic_target = "aws"
  }

  assert {
    condition = (
      one(aws_route53_record.oci_api.weighted_routing_policy).weight == 0 &&
      one(aws_route53_record.aws_api[0].weighted_routing_policy).weight == 100
    )
    error_message = "AWS cutover must atomically set OCI/AWS weights to 0/100."
  }
}

run "reject_aws_target_without_alias" {
  command = plan

  variables {
    traffic_target = "aws"
  }

  expect_failures = [aws_route53_record.oci_api]
}

run "targeted_aws_alias_retains_oci_dependency" {
  command = plan

  variables {
    aws_alb_arn = "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-api/50dc6c495c0c9188"
  }

  plan_options {
    target = [aws_route53_record.aws_api[0]]
  }

  assert {
    condition = (
      aws_route53_record.oci_api.set_identifier == "oci" &&
      one(aws_route53_record.oci_api.weighted_routing_policy).weight == 100 &&
      length(aws_route53_record.aws_api) == 1
    )
    error_message = "Targeting the AWS alias must retain the OCI origin as an upstream dependency."
  }
}

run "reject_cross_account_alb" {
  command = plan

  variables {
    aws_alb_arn = "arn:aws:elasticloadbalancing:ap-northeast-2:111111111111:loadbalancer/app/airbob-api/50dc6c495c0c9188"
  }

  expect_failures = [var.aws_alb_arn]
}

run "reject_untagged_alb" {
  command = plan

  variables {
    aws_alb_arn = "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-api/50dc6c495c0c9188"
  }

  override_data {
    target = data.aws_lb.api
    values = {
      arn                = "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-api/50dc6c495c0c9188"
      dns_name           = "airbob-api-123456789.ap-northeast-2.elb.amazonaws.com"
      zone_id            = "ZWKZPGTI48KDX"
      internal           = false
      load_balancer_type = "application"
      tags               = {}
    }
  }

  expect_failures = [aws_route53_record.oci_api]
}

run "reject_alb_from_another_fenced_run" {
  command = plan

  variables {
    aws_alb_arn = "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-api/50dc6c495c0c9188"
  }

  override_data {
    target = data.aws_lb.api
    values = {
      arn                = "arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/airbob-api/50dc6c495c0c9188"
      dns_name           = "airbob-api-123456789.ap-northeast-2.elb.amazonaws.com"
      zone_id            = "ZWKZPGTI48KDX"
      internal           = false
      load_balancer_type = "application"
      tags = {
        Project      = "airbob"
        Environment  = "performance-lab"
        Stack        = "lab"
        ManagedBy    = "terraform"
        Persistence  = "ephemeral"
        RunId        = "lab-stale-run"
        FencingToken = "41"
      }
    }
  }

  expect_failures = [aws_route53_record.oci_api]
}

run "reject_unsupported_contract_schema" {
  command = plan

  override_data {
    target = data.aws_ssm_parameter.foundation_contract
    values = {
      value = jsonencode({
        schemaVersion = 2
        zone_id       = "Z0123456789EXAMPLE"
        zone_name     = "airbob.cloud"
        api_fqdn      = "api.airbob.cloud"
      })
    }
  }

  expect_failures = [aws_route53_record.oci_api]
}

run "reject_extra_contract_field" {
  command = plan

  override_data {
    target = data.aws_ssm_parameter.foundation_contract
    values = {
      value = jsonencode({
        schemaVersion = 1
        zone_id       = "Z0123456789EXAMPLE"
        zone_name     = "airbob.cloud"
        api_fqdn      = "api.airbob.cloud"
        state_bucket  = "must-not-cross-boundary"
      })
    }
  }

  expect_failures = [aws_route53_record.oci_api]
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

run "reject_noncanonical_oci_ipv4" {
  command = plan

  variables {
    oci_origin_ipv4 = "140.245.76.140/32"
  }

  expect_failures = [var.oci_origin_ipv4]
}
