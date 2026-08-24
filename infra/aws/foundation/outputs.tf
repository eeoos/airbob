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

output "dns_controller_role_arn" {
  description = "Role assumable only by the lab operator for fenced public DNS-state changes."
  value       = aws_iam_role.dns_controller.arn
}

output "image_publisher_role_arn" {
  description = "OIDC role used by the two approved image-publishing workflows."
  value       = aws_iam_role.image_publisher.arn
}

output "dataset_publisher_role_arn" {
  description = "Local-only role used to publish immutable dataset releases and Elasticsearch snapshots; MFA follows the reviewed local-principal policy."
  value       = aws_iam_role.dataset_publisher.arn
}

output "dataset_snapshot_writer_release" {
  description = "The one temporarily writable native Elasticsearch snapshot release, or null when every real release is read-only."
  value       = var.dataset_snapshot_writer_release
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

output "private_dns_zone" {
  description = "Protected private DNS zone anchored by a subnet-free persistent VPC."
  value = {
    id            = aws_route53_zone.private.zone_id
    name          = aws_route53_zone.private.name
    anchor_vpc_id = aws_vpc.private_dns_anchor.id
  }
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

output "expiry_observer" {
  description = "Read-only expiry observer status. Cleanup and DNS mutation are not implemented."
  value = {
    enabled                = local.expiry_alert_delivery_ready
    cleanup_enabled        = false
    schedule_expression    = aws_cloudwatch_event_rule.expiry_observer.schedule_expression
    function_name          = aws_lambda_function.expiry_observer.function_name
    alert_topic_arn        = aws_sns_topic.expiry_alerts.arn
    email_configured       = var.expiry_alert_email != null
    subscription_confirmed = length(aws_sns_topic_subscription.expiry_alert_email) == 1 ? !aws_sns_topic_subscription.expiry_alert_email[0].pending_confirmation : false
  }
}
