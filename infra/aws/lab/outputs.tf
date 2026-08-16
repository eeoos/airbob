output "persistent_resource_contract" {
  description = "Validated, non-secret persistent inputs for later ephemeral lab modules."
  value = {
    dataset_bucket_name     = try(local.lab_contract.dataset_bucket_name, null)
    evidence_bucket_name    = try(local.lab_contract.evidence_bucket_name, null)
    bundle_bucket_name      = try(local.lab_contract.bundle_bucket_name, null)
    lease_table_name        = try(local.lab_contract.lease_table_name, null)
    lease_partition_key     = try(local.lab_contract.lease_partition_key, null)
    lease_expires_attribute = try(local.lab_contract.lease_expires_attribute, null)
    lease_lock_id           = try(local.lab_contract.lease_lock_id, null)
    zone_id                 = try(local.lab_contract.zone_id, null)
    api_fqdn                = try(local.lab_contract.api_fqdn, null)
    api_certificate_arn     = try(local.lab_contract.api_certificate_arn, null)
    ecr_repositories        = local.ecr_repositories
  }

  precondition {
    condition     = local.caller_identity_valid
    error_message = "The active AWS caller and region must match the pinned lab-state contract."
  }

  precondition {
    condition     = local.contract_schema_valid
    error_message = "The foundation lab contract has an unsupported schema, account, region, or field set."
  }

  precondition {
    condition     = local.state_boundaries_valid
    error_message = "The lab contract must retain the exact state keys and deterministic persistent buckets."
  }

  precondition {
    condition     = local.operational_contract_valid
    error_message = "The lab contract has an unsupported lease, DNS, or certificate boundary."
  }

  precondition {
    condition     = local.ecr_contract_valid
    error_message = "The lab contract must expose exactly the ten approved ECR URL/ARN pairs."
  }
}

output "state_boundaries" {
  description = "Validated state-key identities; this root never reads the foundation state object."
  value = {
    bootstrap  = try(local.lab_contract.bootstrap_state_key, null)
    foundation = try(local.lab_contract.foundation_state_key, null)
    dns        = try(local.lab_contract.dns_state_key, null)
    lab        = try(local.lab_contract.lab_state_key, null)
  }
}
