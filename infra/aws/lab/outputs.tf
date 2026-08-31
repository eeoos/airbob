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
    fencing_token                = var.fencing_token
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

output "phase3_contract" {
  description = "Non-secret dataset, RDS, and ordered bootstrap status."
  value = {
    dataset_release            = var.dataset_release
    dataset_run_id             = try(local.dataset_manifest.datasetRunId, null)
    release_kind               = local.dataset_release_kind
    profile_version            = local.dataset_profile_version
    production_spec_key        = try(local.dataset_profile_contract.production_spec_key, null)
    database_bootstrap         = var.database_bootstrap
    rds_instance_id            = local.services_enabled ? module.rds[0].id : null
    rds_resource_id            = local.services_enabled ? module.rds[0].resource_id : null
    rds_endpoint               = local.services_enabled ? module.rds[0].address : null
    rds_engine_version         = var.rds_engine_version
    rds_parameter_group        = local.services_enabled ? module.rds[0].parameter_group_name : null
    rds_configured_storage_gib = local.services_enabled ? module.rds[0].contract.configured_storage_gib : null
    search_enabled             = local.dataset_search_enabled
    data_ready                 = local.data_ready && local.data_bootstrap_receipt_valid
    data_bootstrap_receipt_key = local.data_ready ? "data-bootstrap/${var.run_id}/${var.dataset_release}.json" : null
  }

  precondition {
    condition     = local.dataset_release_valid && local.dataset_snapshot_valid
    error_message = "Phase 3 output is unavailable because its dataset or optional snapshot contract is invalid."
  }

  precondition {
    condition     = local.data_bootstrap_receipt_valid
    error_message = "The data-ready output is unavailable because the ordered bootstrap receipt is invalid."
  }
}

output "phase4_contract" {
  description = "Non-secret ALB, application-capacity, scaling, refresh, and load-generator contract."
  value = {
    app_enabled                        = var.app_enabled
    mode                               = var.mode
    measurement_policy                 = var.measurement_policy
    accommodation_detail_cache_enabled = var.accommodation_detail_cache_enabled
    instance_type                      = local.services_enabled ? module.app_asg[0].contract.instance_type : "c6i.large"
    capacity = local.services_enabled ? {
      min     = module.app_asg[0].contract.min
      desired = module.app_asg[0].contract.desired
      max     = module.app_asg[0].contract.max
    } : local.app_capacity
    app_subnet_count                    = local.services_enabled ? module.app_asg[0].contract.subnet_count : length(local.app_subnet_ids)
    app_availability_zones              = local.app_availability_zones
    scaling_policy_count                = local.services_enabled ? module.app_asg[0].contract.scaling_policy_count : 0
    request_count_per_target_per_minute = local.services_enabled ? module.app_asg[0].contract.request_target_per_minute : var.request_count_per_target_per_minute
    cpu_target_percent                  = local.services_enabled ? module.app_asg[0].contract.cpu_target_percent : 50
    default_instance_warmup             = local.services_enabled ? module.app_asg[0].contract.default_instance_warmup : 180
    runtime_revision                    = local.app_runtime_revision
    alb_arn                             = local.services_enabled ? module.alb[0].arn : null
    alb_dns_name                        = local.services_enabled ? module.alb[0].dns_name : null
    alb_zone_id                         = local.services_enabled ? module.alb[0].zone_id : null
    target_group_arn                    = local.services_enabled ? module.alb[0].target_group_arn : null
    auto_scaling_group_name             = local.services_enabled ? module.app_asg[0].name : null
    alb_https_only                      = local.services_enabled ? module.alb[0].contract.https_only : true
    alb_stickiness_enabled              = local.services_enabled ? module.alb[0].contract.stickiness_enabled : false
    load_generator_enabled              = var.load_generator_enabled
    load_generator_instance_type        = local.services_enabled && var.load_generator_enabled ? module.load_generator[0].contract.instance_type : null
    load_generator_instance_id          = local.services_enabled && var.load_generator_enabled ? module.load_generator[0].instance_id : null
    load_generator_public_ipv4          = local.services_enabled && var.load_generator_enabled ? module.load_generator[0].contract.public_ipv4 : false
    refresh = local.services_enabled ? module.app_asg[0].contract.refresh : {
      min_healthy_percentage = var.mode == "performance" ? 0 : 100
      max_healthy_percentage = var.mode == "performance" ? 100 : 200
      checkpoint_percentages = var.mode == "scaling" ? [50, 100] : []
      auto_rollback          = true
    }
    refresh_completion_gate = "Phase 5 controller must poll the asynchronous instance refresh for at most 15 minutes before DNS switch."
  }

}
