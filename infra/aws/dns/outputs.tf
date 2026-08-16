output "api_fqdn" {
  description = "Public API name owned by the weighted DNS state."
  value       = try(local.dns_contract.api_fqdn, null)
}

output "oci_record" {
  description = "Protected always-on OCI weighted origin."
  value = {
    set_identifier = aws_route53_record.oci_api.set_identifier
    weight         = one(aws_route53_record.oci_api.weighted_routing_policy).weight
    ttl            = aws_route53_record.oci_api.ttl
    ipv4           = one(aws_route53_record.oci_api.records)
  }
}

output "aws_record_staged" {
  description = "Whether the optional AWS ALB alias exists at its initial zero weight."
  value       = local.aws_alias_enabled
}

output "traffic_target" {
  description = "Origin currently selected by the weighted DNS state."
  value       = var.traffic_target
}
