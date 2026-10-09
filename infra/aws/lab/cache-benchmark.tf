# Opt-in topology only. The measurement runner switches profiles temporarily on these hosts.
variable "cache_benchmark_enabled" {
  type        = bool
  default     = false
  description = "Enable a separate load generator for an already qualified V28 B application."
}

variable "cache_benchmark_app_count" {
  type        = number
  default     = 1
  description = "Fixed application count for cache comparisons; no autoscaling policy is installed."
  validation {
    condition     = contains([1, 2, 3, 4], var.cache_benchmark_app_count)
    error_message = "Cache comparisons use 1-4 fixed app instances."
  }
}

check "cache_benchmark_topology" {
  assert {
    condition = !var.cache_benchmark_enabled || (
      var.global_b_services && local.data_ready && var.app_enabled && var.load_generator_enabled &&
      !var.global_b_service_bootstrap_enabled && var.mode == "performance" && var.dns_mode == "direct-only" &&
      (var.lab_power == null || local.power_phase == "running")
    )
    error_message = "Cache measurement requires an admitted V28 B app, fixed performance topology and a separate load generator."
  }
}

resource "aws_vpc_security_group_ingress_rule" "cache_benchmark_loadgen" {
  count = var.cache_benchmark_enabled && var.load_generator_enabled ? 1 : 0

  security_group_id = module.security.security_group_ids.alb
  cidr_ipv4         = "${module.load_generator[0].public_ip}/32"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "HTTPS only from the current cache benchmark generator"
  tags              = local.ephemeral_tags
}
