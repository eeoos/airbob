output "state_bucket_name" {
  description = "Persistent Terraform state bucket created by bootstrap."
  value       = var.state_bucket_name
}

output "state_keys" {
  description = "Canonical, isolated Terraform state keys."
  value       = local.state_keys
}

output "application_bucket_name" {
  description = "Existing application object bucket read as data only."
  value       = data.aws_s3_bucket.application.id
}

output "dataset_bucket_name" {
  description = "Persistent dataset release bucket."
  value       = aws_s3_bucket.managed["dataset"].id
}

output "evidence_bucket_name" {
  description = "Persistent tagged performance-evidence bucket."
  value       = aws_s3_bucket.managed["evidence"].id
}

output "bundle_bucket_name" {
  description = "Persistent immutable service-bundle release bucket."
  value       = aws_s3_bucket.managed["bundle"].id
}

output "ecr_repository_urls" {
  description = "Exact ECR repository URLs consumed by immutable image publication."
  value       = local.ecr_repository_urls
}

output "ecr_repository_arns" {
  description = "Exact ECR repository ARNs used to scope lab image-pull permissions."
  value       = local.ecr_repository_arns
}

output "foundation_admin_role_arn" {
  description = "OIDC/local role for persistent foundation administration."
  value       = aws_iam_role.foundation_admin.arn
}

output "lab_operator_role_arn" {
  description = "OIDC/local role with foundation-base lab permissions only."
  value       = aws_iam_role.lab_operator.arn
}

output "image_publisher_role_arn" {
  description = "OIDC role used by the two approved image-publishing workflows."
  value       = aws_iam_role.image_publisher.arn
}

output "lease_contract" {
  description = "Persistent orchestration lease identifiers. DynamoDB TTL is intentionally disabled."
  value = {
    table_name        = aws_dynamodb_table.orchestration_lease.name
    table_arn         = aws_dynamodb_table.orchestration_lease.arn
    partition_key     = local.lease_partition_key
    expires_attribute = local.lease_expires_attribute
    lock_id           = local.lease_lock_id
  }
}

output "public_zone_id" {
  description = "New Route 53 public hosted zone ID for airbob.cloud."
  value       = aws_route53_zone.public.zone_id
}

output "public_zone_name_servers" {
  description = "Name servers that must replace Gabia's authoritative NS only after inventory review."
  value       = aws_route53_zone.public.name_servers
}

output "api_certificate_arn" {
  description = "ACM certificate ARN for api.airbob.cloud."
  value       = aws_acm_certificate.api.arn
}

output "api_certificate_status" {
  description = "ACM status; ISSUED is required before any AWS traffic switch."
  value       = aws_acm_certificate.api.status
}

output "dns_delegation_confirmed" {
  description = "Whether the second-pass ACM validation gate is enabled."
  value       = var.dns_delegation_confirmed
}

output "foundation_contract_parameter_names" {
  description = "Narrow non-secret SSM contracts consumed by the DNS and lab roots."
  value = {
    dns = aws_ssm_parameter.dns_consumer_contract.name
    lab = aws_ssm_parameter.lab_consumer_contract.name
  }
}
