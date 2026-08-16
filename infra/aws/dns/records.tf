resource "aws_route53_record" "oci_api" {
  zone_id = try(local.dns_contract.zone_id, "")
  name    = try(local.dns_contract.api_fqdn, "api.airbob.cloud")
  type    = "A"
  ttl     = 60
  records = [var.oci_origin_ipv4]

  set_identifier = "oci"

  weighted_routing_policy {
    weight = 100
  }

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = local.caller_identity_valid
      error_message = "The active AWS caller and region must match the pinned DNS-state contract."
    }

    precondition {
      condition     = local.dns_contract_valid
      error_message = "The foundation DNS contract has an unsupported schema or value set."
    }

    precondition {
      condition     = local.aws_alias_valid
      error_message = "The staged AWS alias must resolve to the tagged internet-facing application load balancer."
    }
  }
}

resource "aws_route53_record" "aws_api" {
  count = local.aws_alias_enabled ? 1 : 0

  depends_on = [aws_route53_record.oci_api]

  zone_id = try(local.dns_contract.zone_id, "")
  name    = try(local.dns_contract.api_fqdn, "api.airbob.cloud")
  type    = "A"

  set_identifier = "aws"

  weighted_routing_policy {
    weight = 0
  }

  alias {
    name                   = data.aws_lb.api[0].dns_name
    zone_id                = data.aws_lb.api[0].zone_id
    evaluate_target_health = true
  }

  lifecycle {
    precondition {
      condition     = local.caller_identity_valid
      error_message = "The active AWS caller and region must match the pinned DNS-state contract."
    }

    precondition {
      condition     = local.dns_contract_valid
      error_message = "The foundation DNS contract has an unsupported schema or value set."
    }

    precondition {
      condition     = local.aws_alias_valid
      error_message = "The staged AWS alias must resolve to the tagged internet-facing application load balancer."
    }
  }
}
