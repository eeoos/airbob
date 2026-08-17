output "state_bucket_name" {
  description = "S3 bucket used by the bootstrap, foundation, DNS, and lab states."
  value       = aws_s3_bucket.state.id
}

output "state_bucket_arn" {
  description = "ARN of the persistent Terraform state bucket."
  value       = aws_s3_bucket.state.arn
}

output "bootstrap_state_key" {
  description = "Remote key used after the one-time bootstrap migration."
  value       = "airbob/bootstrap/terraform.tfstate"
}
