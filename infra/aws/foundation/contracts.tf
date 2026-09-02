locals {
  dns_consumer_contract = {
    schemaVersion = 1
    zone_id       = aws_route53_zone.public.zone_id
    zone_name     = local.zone_name
    api_fqdn      = local.api_fqdn
  }

  lab_consumer_contract = {
    schemaVersion                    = 1
    account_id                       = var.account_id
    region                           = var.aws_region
    state_bucket_name                = var.state_bucket_name
    bootstrap_state_key              = local.state_keys.bootstrap
    foundation_state_key             = local.state_keys.foundation
    dns_state_key                    = local.state_keys.dns
    lab_state_key                    = local.state_keys.lab
    dataset_bucket_name              = aws_s3_bucket.managed["dataset"].id
    evidence_bucket_name             = aws_s3_bucket.managed["evidence"].id
    bundle_bucket_name               = aws_s3_bucket.managed["bundle"].id
    lease_table_name                 = aws_dynamodb_table.orchestration_lease.name
    lease_partition_key              = local.lease_partition_key
    lease_expires_attribute          = local.lease_expires_attribute
    lease_lock_id                    = local.lease_lock_id
    zone_id                          = aws_route53_zone.public.zone_id
    api_fqdn                         = local.api_fqdn
    api_certificate_arn              = aws_acm_certificate.api.arn
    private_dns_zone_id              = aws_route53_zone.private.zone_id
    private_dns_zone_name            = aws_route53_zone.private.name
    approved_rds_snapshot_identifier = var.approved_rds_snapshot_identifier
    ecr_repositories                 = local.ecr_repositories
  }
}

resource "aws_ssm_parameter" "dns_consumer_contract" {
  name        = "/airbob/performance-lab/foundation/dns-contract"
  description = "Non-secret, allowlisted Route 53 contract for the DNS state."
  type        = "String"
  tier        = "Standard"
  value       = jsonencode(local.dns_consumer_contract)

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_ssm_parameter" "lab_consumer_contract" {
  name        = "/airbob/performance-lab/foundation/lab-contract"
  description = "Non-secret, allowlisted persistent resource contract for the lab state."
  type        = "String"
  tier        = "Standard"
  value       = jsonencode(local.lab_consumer_contract)

  lifecycle {
    prevent_destroy = true
  }
}
