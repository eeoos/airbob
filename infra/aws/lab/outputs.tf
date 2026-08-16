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
    private_dns_zone_id     = try(local.lab_contract.private_dns_zone_id, null)
    private_dns_zone_name   = try(local.lab_contract.private_dns_zone_name, null)
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

output "phase2_contract" {
  description = "Non-secret Phase 2 topology and ordered transition status."
  value = {
    run_id                       = var.run_id
    deployment_phase             = var.deployment_phase
    vpc_id                       = module.network.vpc_id
    primary_private_route_table  = module.network.private_route_table_ids.primary
    s3_gateway_endpoint_id       = module.network.s3_endpoint_id
    nat_instance_id              = module.nat.instance_id
    nat_public_ip                = module.nat.public_ip
    probe_enabled                = local.probe_enabled
    probe_instance_id            = try(module.egress_probe.instance_ids["egress-probe"], null)
    expected_network_receipt_key = local.probe_enabled ? try("network-receipts/${var.run_id}/${module.egress_probe.instance_ids["egress-probe"]}.json", null) : "network-receipts/${var.run_id}/${var.verified_probe_instance_id}.json"
    services                     = module.service_hosts.instance_ids
    private_dns_zone_id          = local.lab_contract.private_dns_zone_id
    instance_types               = { for service, host in local.service_hosts : service => host.instance_type }
    redis_topology = {
      host_count      = 1
      redis_processes = 2
      exporters       = 2
      general         = "redis-general.lab.airbob.internal:6379"
      cache           = "redis-cache.lab.airbob.internal:6380"
    }
  }

  precondition {
    condition     = local.network_receipt_valid && local.probe_clearance_valid
    error_message = "Phase 2 output is unavailable because its required transition receipts are invalid."
  }

  precondition {
    condition     = local.bundle_release_valid && local.phase2_images_valid
    error_message = "Phase 2 service output is unavailable because the immutable runtime release is invalid."
  }
}
