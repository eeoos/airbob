locals {
  expected_contract_keys = toset([
    "schemaVersion",
    "zone_id",
    "zone_name",
    "api_fqdn",
  ])

  dns_contract      = try(jsondecode(nonsensitive(data.aws_ssm_parameter.foundation_contract.value)), {})
  dns_contract_keys = toset(try(keys(local.dns_contract), []))
  caller_identity_valid = (
    data.aws_caller_identity.current.account_id == var.account_id &&
    data.aws_region.current.region == var.aws_region
  )
  dns_contract_valid = try(
    local.dns_contract_keys == local.expected_contract_keys &&
    local.dns_contract.schemaVersion == 1 &&
    local.dns_contract.zone_name == "airbob.cloud" &&
    local.dns_contract.api_fqdn == "api.airbob.cloud" &&
    can(regex("^Z[A-Z0-9]+$", local.dns_contract.zone_id)),
    false,
  )
  aws_alias_enabled = var.aws_alb_arn != null
  traffic_target_valid = (
    var.traffic_target == "oci" ||
    (var.traffic_target == "aws" && local.aws_alias_enabled)
  )
  aws_alias_valid = !local.aws_alias_enabled || try(
    !data.aws_lb.api[0].internal &&
    data.aws_lb.api[0].load_balancer_type == "application" &&
    data.aws_lb.api[0].tags.Project == "airbob" &&
    data.aws_lb.api[0].tags.Environment == "performance-lab" &&
    data.aws_lb.api[0].tags.Stack == "lab" &&
    data.aws_lb.api[0].tags.ManagedBy == "terraform" &&
    data.aws_lb.api[0].tags.Persistence == "ephemeral" &&
    var.run_id != null &&
    var.fencing_token != null &&
    data.aws_lb.api[0].tags.RunId == var.run_id &&
    data.aws_lb.api[0].tags.FencingToken == tostring(var.fencing_token),
    false,
  )
}
