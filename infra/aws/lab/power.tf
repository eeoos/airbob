# Explicit local lifecycle control. Normal service applies do not select this.
# State entries control existing instances; they never create EC2/RDS instances.
locals {
  power_processes = toset(["Launch", "Terminate", "HealthCheck", "ReplaceUnhealthy", "AZRebalance", "AlarmNotification", "ScheduledActions", "AddToLoadBalancer", "InstanceRefresh"])
  power_phase     = var.lab_power == null ? "inactive" : var.lab_power.phase
  power_suspended = var.lab_power == null ? toset([]) : (local.power_phase == "running" ? var.lab_power.original_suspended_processes : local.power_processes)
  power_ids       = var.lab_power == null ? {} : merge(module.service_hosts.instance_ids, { nat = module.nat.instance_id, app = var.lab_power.app_instance_id })
  power_states = { for name, id in local.power_ids : name => (
    local.power_phase == "stopped" ||
    (name == "app" && contains(["writers-stopped", "dependencies-running", "connect-running"], local.power_phase)) ||
    (name == "debezium" && contains(["writers-stopped", "dependencies-running"], local.power_phase))
  ) ? "stopped" : "running" }
  power_rds_state = local.power_phase == "stopped" ? "stopped" : "available"
  power_rds_request = var.lab_power == null ? null : {
    schemaVersion     = 1, kind = "airbob-rds-power-request", operationId = var.lab_power.operation_id
    runId             = var.run_id, resourceFencingToken = var.fencing_token, expiresAt = tonumber(var.expires_at)
    deadlineEpoch     = var.lab_power.deadline_epoch, desiredState = local.power_rds_state
    identifier        = module.rds[0].identifier, resourceId = var.lab_power.rds_resource_id
    region            = var.aws_region, accountId = var.account_id, lease = var.lab_power.lease
    evidenceDirectory = var.lab_power.evidence_directory
  }
}

resource "aws_ec2_instance_state" "power" {
  for_each    = local.power_ids
  instance_id = each.value
  state       = local.power_states[each.key]
  force       = false

  # The first fenced apply must finish before an instance may be stopped.
  depends_on = [module.app_asg]

  lifecycle {
    precondition {
      condition     = var.global_b_services && var.app_enabled && var.deployment_phase == "data-ready" && var.mode == "performance" && !var.load_generator_enabled && !var.global_b_service_bootstrap_enabled && var.global_b_readiness_receipt != null
      error_message = "Power control requires the existing ready B application, without another bootstrap."
    }
    precondition {
      condition     = toset(keys(local.power_ids)) == toset(["app", "debezium", "elasticsearch", "kafka", "monitoring", "nat", "redis"]) && length(toset(values(local.power_ids))) == 7
      error_message = "Power control must retain exactly the original seven instances."
    }
  }
}

# AWS provider 6.55.0 waits for 'available' BEFORE calling StartDBInstance.
# Its native RDS state resource therefore cannot start a stopped instance.
# This single controller uses the caller's credentials inside Terraform apply;
# there is intentionally no competing aws_rds_instance_state resource.
resource "terraform_data" "rds_power" {
  count            = var.lab_power == null ? 0 : 1
  input            = local.power_rds_request
  triggers_replace = [var.lab_power.operation_id, local.power_rds_state, filesha256("${path.module}/../scripts/growth_b_rds_power.py")]

  provisioner "local-exec" {
    command = "python3 \"${path.module}/../scripts/growth_b_rds_power.py\""
    environment = {
      AIRBOB_RDS_POWER_REQUEST = jsonencode(self.input)
    }
    quiet = true
  }

  lifecycle {
    precondition {
      condition     = var.lab_power.rds_resource_id == module.rds[0].resource_id && var.rds_instance_class == "db.t3.small" && var.rds_engine_version == "8.4.11"
      error_message = "RDS power control may target only the retained small B database."
    }
  }
}

output "lab_power" {
  value = var.lab_power == null ? null : {
    request                      = var.lab_power
    phase                        = local.power_phase, operation_id = var.lab_power.operation_id
    instance_ids                 = local.power_ids, desired_states = local.power_states
    rds_resource_id              = var.lab_power.rds_resource_id, rds_state = local.power_rds_state
    original_suspended_processes = var.lab_power.original_suspended_processes
  }
}
