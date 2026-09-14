variable "lab_power" {
  description = "Explicit local pause/resume request; null leaves normal services unchanged."
  type = object({
    operation_id                 = string
    phase                        = string
    app_instance_id              = string
    rds_resource_id              = string
    identity_sha256              = string
    original_suspended_processes = set(string)
    deadline_epoch               = number
    evidence_directory           = string
    lease                        = object({ table = string, lockName = string, owner = string, runId = string, command = string, fencingToken = number })
  })
  default = null
  validation {
    condition = var.lab_power == null ? true : (
      can(regex("^[a-z0-9][a-z0-9-]{2,47}$", var.lab_power.operation_id)) &&
      contains(["fenced", "writers-stopped", "stopped", "dependencies-running", "connect-running", "app-running", "running"], var.lab_power.phase) &&
      can(regex("^i-[0-9a-f]{17}$", var.lab_power.app_instance_id)) && can(regex("^db-[A-Z0-9]+$", var.lab_power.rds_resource_id)) &&
      can(regex("^[0-9a-f]{64}$", var.lab_power.identity_sha256)) &&
      startswith(var.lab_power.evidence_directory, "/") && var.lab_power.deadline_epoch <= tonumber(var.expires_at) &&
      var.lab_power.lease.runId == var.run_id && var.lab_power.lease.command == "up" &&
      length(setsubtract(var.lab_power.original_suspended_processes, toset(["Launch", "Terminate", "HealthCheck", "ReplaceUnhealthy", "AZRebalance", "AlarmNotification", "ScheduledActions", "AddToLoadBalancer", "InstanceRefresh"]))) == 0
    )
    error_message = "Power control needs a bounded same-run request and the captured original ASG processes."
  }
}

variable "account_id" {
  description = "AWS account that may contain ephemeral Airbob lab resources."
  type        = string
  default     = "942632789808"

  validation {
    condition     = var.account_id == "942632789808"
    error_message = "The lab state may only target AWS account 942632789808."
  }
}

variable "aws_region" {
  description = "AWS region for the Airbob performance lab."
  type        = string
  default     = "ap-northeast-2"

  validation {
    condition     = var.aws_region == "ap-northeast-2"
    error_message = "The lab state may only target ap-northeast-2."
  }
}

variable "run_id" {
  description = "Stable identifier for one ephemeral lab run and its evidence prefix."
  type        = string

  validation {
    condition = (
      can(regex("^lab-[a-z0-9][a-z0-9-]{0,27}$", var.run_id)) &&
      !endswith(var.run_id, "-") &&
      !strcontains(var.run_id, "--")
    )
    error_message = "run_id must start with lab-, contain 5-32 lower-case letters, digits, or hyphens, end with an alphanumeric character, and contain no consecutive hyphens."
  }
}

variable "expires_at" {
  description = "Canonical Unix-seconds expiry copied to every ephemeral resource tag."
  type        = string

  validation {
    condition     = can(regex("^[1-9][0-9]{9}$", var.expires_at))
    error_message = "expires_at must be exactly ten decimal Unix-seconds digits."
  }
}

variable "fencing_token" {
  description = "Monotonically increasing orchestration-lease token copied to every ephemeral resource tag."
  type        = number

  validation {
    condition     = var.fencing_token >= 1 && floor(var.fencing_token) == var.fencing_token
    error_message = "fencing_token must be a positive integer issued by the orchestration lease."
  }
}

variable "deployment_phase" {
  description = "Ordered lab transition; data-ready attests the completed Phase 3 bootstrap."
  type        = string
  default     = "network"

  validation {
    condition     = contains(["network", "probe-cleared", "services", "data-ready"], var.deployment_phase)
    error_message = "deployment_phase must be network, probe-cleared, services, or data-ready."
  }
}

variable "dns_mode" {
  description = "Explicit public-origin posture: direct-only leaves Route 53 on OCI, while cutover enables the existing DNS workflow."
  type        = string

  validation {
    condition     = contains(["direct-only", "cutover"], var.dns_mode)
    error_message = "dns_mode must be direct-only or cutover."
  }
}

variable "alb_ingress_cidr" {
  description = "HTTPS source CIDR: one canonical public IPv4 /32 in direct-only mode, or exactly 0.0.0.0/0 in cutover mode."
  type        = string

  validation {
    condition = (
      var.dns_mode == "cutover"
      ? var.alb_ingress_cidr == "0.0.0.0/0"
      : try(
        can(regex("^([0-9]{1,3}\\.){3}[0-9]{1,3}/32$", var.alb_ingress_cidr)) &&
        var.alb_ingress_cidr == "${cidrhost(var.alb_ingress_cidr, 0)}/32" &&
        tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) >= 1 &&
        tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) <= 223 &&
        !contains([10, 127], tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0])) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 100 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) >= 64 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) <= 127
        ) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 169 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) == 254
        ) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 172 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) >= 16 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) <= 31
        ) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 192 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) == 168
        ) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 192 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) == 0 &&
          contains([0, 2], tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[2]))
        ) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 192 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) == 88 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[2]) == 99
        ) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 198 &&
          contains([18, 19], tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]))
        ) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 198 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) == 51 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[2]) == 100
        ) &&
        !(
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[0]) == 203 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[1]) == 0 &&
          tonumber(split(".", split("/", var.alb_ingress_cidr)[0])[2]) == 113
        ),
        false,
      )
    )
    error_message = "direct-only requires one canonical public IPv4 /32; cutover requires exactly 0.0.0.0/0."
  }
}

variable "ami_id" {
  description = "Reviewed Amazon Linux 2023 x86_64 AMI used by NAT, probe, and every Phase 2 service host."
  type        = string

  validation {
    condition     = can(regex("^ami-[0-9a-f]{8,17}$", var.ami_id))
    error_message = "ami_id must be a canonical EC2 AMI id."
  }
}

variable "primary_availability_zone" {
  description = "Recorded AZ for NAT and all single-node stateful dependencies."
  type        = string
  default     = "ap-northeast-2a"

  validation {
    condition     = var.primary_availability_zone == "ap-northeast-2a"
    error_message = "Phase 2 pins the primary dependency AZ to ap-northeast-2a."
  }
}

variable "secondary_availability_zone" {
  description = "Second AZ used by scaling mode."
  type        = string
  default     = "ap-northeast-2c"

  validation {
    condition     = var.secondary_availability_zone == "ap-northeast-2c"
    error_message = "Phase 2 pins the secondary AZ to ap-northeast-2c."
  }
}

variable "verified_probe_instance_id" {
  description = "Instance id attested by the egress receipt; required after the network phase."
  type        = string
  default     = ""

  validation {
    condition = (
      var.deployment_phase == "network" ||
      can(regex("^i-[0-9a-f]{8,17}$", var.verified_probe_instance_id))
    )
    error_message = "verified_probe_instance_id is required after the network phase."
  }
}

variable "bundle_commit" {
  description = "Full Git commit of the immutable service-bundle release."
  type        = string
  default     = ""

  validation {
    condition = (
      !contains(["services", "data-ready"], var.deployment_phase) ||
      can(regex("^[0-9a-f]{40}$", var.bundle_commit))
    )
    error_message = "services phase requires a full lower-case 40-character bundle commit."
  }
}

variable "bundle_sha256" {
  description = "SHA-256 of the immutable service-bundle archive."
  type        = string
  default     = ""

  validation {
    condition = (
      !contains(["services", "data-ready"], var.deployment_phase) ||
      can(regex("^[0-9a-f]{64}$", var.bundle_sha256))
    )
    error_message = "services phase requires the bundle archive SHA-256."
  }
}

variable "infra_image_references" {
  description = "Exact Phase 2 ECR repository@sha256 references keyed by bundle image variable."
  type        = map(string)
  default     = {}
}

variable "app_image_reference" {
  description = "Exact immutable amd64 application ECR repository@sha256 reference."
  type        = string
  default     = ""
}

variable "app_enabled" {
  description = "Internal capacity gate; false keeps the App ASG at 0/0/0 during data bootstrap."
  type        = bool
  default     = false
}

variable "global_b_prepare_only" {
  description = "Explicit B/V28 data-only preparation; never creates an app ASG/ALB or starts CDC."
  type        = bool
  default     = false

  validation {
    condition = !var.global_b_prepare_only || (
      !var.app_enabled && !var.load_generator_enabled && var.dns_mode == "direct-only" && var.mode == "performance" &&
      var.database_bootstrap == "dump" && var.deployment_phase != "data-ready" &&
      can(regex("^global-growth-b-[0-9a-f]{16}$", var.dataset_release)) &&
      can(regex("^[A-Za-z0-9._~+/=-]+$", var.global_b_manifest_version_id)) && !contains(["null", "None"], var.global_b_manifest_version_id) &&
      can(regex("^[A-Za-z0-9._:@/-]{3,128}$", var.global_b_lease_owner))
    )
    error_message = "B preparation needs exact B inputs and live lease owner, dump/direct-only/performance, and no app/load/data-ready transition."
  }
}

variable "global_b_import_from_mac" {
  description = "Import the sealed B dump from Mac through the existing NAT SSM tunnel, without a preparation EC2 or host bootstrap."
  type        = bool
  default     = false

  validation {
    condition     = !var.global_b_import_from_mac || var.global_b_prepare_only
    error_message = "Mac import is available only with global_b_prepare_only."
  }
}

variable "global_b_manifest_version_id" {
  description = "Exact S3 VersionId of the reviewed B preparation or service wrapper."
  type        = string
  default     = ""
}

variable "global_b_lease_owner" {
  description = "Exact current controller lease owner; no credentials or session tokens."
  type        = string
  default     = ""
}

variable "mode" {
  description = "Application capacity mode: single-node performance or elastic scaling."
  type        = string
  default     = "performance"

  validation {
    condition     = contains(["performance", "scaling"], var.mode)
    error_message = "mode must be performance or scaling."
  }
}

variable "measurement_policy" {
  description = "Application background-work policy used by the selected experiment."
  type        = string
  default     = "isolated-read"

  validation {
    condition     = contains(["integrated-smoke", "isolated-read"], var.measurement_policy)
    error_message = "measurement_policy must be integrated-smoke or isolated-read."
  }
}

variable "accommodation_detail_cache_enabled" {
  description = "Explicit same-image accommodation-detail cache A/B toggle."
  type        = bool
  default     = true
}

variable "request_count_per_target_per_minute" {
  description = "Baseline-derived ALBRequestCountPerTarget one-minute target; non-null only for enabled scaling mode."
  type        = number
  default     = null

  validation {
    condition = (
      var.request_count_per_target_per_minute == null ||
      (
        var.request_count_per_target_per_minute >= 1 &&
        var.request_count_per_target_per_minute <= 1000000
      )
    )
    error_message = "request_count_per_target_per_minute must be null or between 1 and 1,000,000."
  }
}

variable "load_generator_enabled" {
  description = "Create the no-ingress c6i.xlarge public-subnet load generator for a recorded run."
  type        = bool
  default     = false
}

variable "dataset_release" {
  description = "Immutable dataset release selected before Phase 3 planning."
  type        = string
  default     = ""

  validation {
    condition = (
      !contains(["services", "data-ready"], var.deployment_phase) ||
      can(regex("^[a-z0-9][a-z0-9._-]{2,63}$", var.dataset_release))
    )
    error_message = "services and data-ready require a canonical dataset_release."
  }
}

variable "dataset_manifest_sha256" {
  description = "SHA-256 of the release manifest published last as the dataset completion marker."
  type        = string
  default     = ""

  validation {
    condition = (
      !contains(["services", "data-ready"], var.deployment_phase) ||
      can(regex("^[0-9a-f]{64}$", var.dataset_manifest_sha256))
    )
    error_message = "services and data-ready require the exact dataset manifest SHA-256."
  }
}

variable "database_bootstrap" {
  description = "RDS creation path selected before plan; dump is canonical and snapshot is only a validated cache."
  type        = string
  default     = ""

  validation {
    condition = (
      !contains(["services", "data-ready"], var.deployment_phase) ||
      contains(["dump", "snapshot"], var.database_bootstrap)
    )
    error_message = "services and data-ready require database_bootstrap=dump or snapshot."
  }
}

variable "rds_snapshot_identifier" {
  description = "Prevalidated persistent dataset snapshot used only when database_bootstrap=snapshot."
  type        = string
  default     = ""

  validation {
    condition = (
      var.database_bootstrap == "snapshot" ? (
        can(regex("^airbob-dataset-[a-z0-9][a-z0-9-]{2,47}$", var.rds_snapshot_identifier)) &&
        !endswith(var.rds_snapshot_identifier, "-") &&
        !strcontains(var.rds_snapshot_identifier, "--")
      ) : var.rds_snapshot_identifier == ""
    )
    error_message = "snapshot bootstrap requires a valid airbob-dataset-* RDS snapshot identifier without trailing or consecutive hyphens; every other bootstrap mode requires it to be empty."
  }
}

variable "rds_snapshot_source_run_id" {
  description = "Exact canonical Lab run ID recorded on the promoted RDS snapshot used for snapshot bootstrap."
  type        = string
  default     = ""

  validation {
    condition = (
      var.database_bootstrap == "snapshot" ? (
        can(regex("^lab-[a-z0-9][a-z0-9-]{0,27}$", var.rds_snapshot_source_run_id)) &&
        !endswith(var.rds_snapshot_source_run_id, "-") &&
        !strcontains(var.rds_snapshot_source_run_id, "--")
      ) : var.rds_snapshot_source_run_id == ""
    )
    error_message = "snapshot bootstrap requires the canonical lab-* source run ID; every other bootstrap mode requires it to be empty."
  }
}

variable "rds_snapshot_source_resource_id" {
  description = "Exact immutable RDS resource ID recorded on the promoted snapshot used for snapshot bootstrap."
  type        = string
  default     = ""

  validation {
    condition = (
      var.database_bootstrap == "snapshot"
      ? can(regex("^db-[A-Z0-9]+$", var.rds_snapshot_source_resource_id))
      : var.rds_snapshot_source_resource_id == ""
    )
    error_message = "snapshot bootstrap requires the exact db-* source RDS resource ID; every other bootstrap mode requires it to be empty."
  }
}

variable "rds_instance_class" {
  description = "Initial class selection. Global B may explicitly select db.m6i.large; retained steps inherit the original selection."
  type        = string
  default     = "db.t3.small"
  validation {
    condition = var.rds_instance_class == "db.t3.small" || (
      var.rds_instance_class == "db.m6i.large" && var.rds_engine_version == "8.4.11" &&
      (var.global_b_prepare_only || var.global_b_services || var.global_b_snapshot_restore_only)
    )
    error_message = "db.m6i.large is an explicit Global B selection only; arbitrary or classic class changes are forbidden."
  }
}

variable "rds_engine_version" {
  description = "Exact reviewed MySQL patch; B data-only preparation requires 8.4.11."
  type        = string
  default     = ""

  validation {
    condition = (
      (var.global_b_prepare_only || var.global_b_services || var.global_b_snapshot_restore_only)
      ? var.rds_engine_version == "8.4.11"
      : (!contains(["services", "data-ready"], var.deployment_phase) || can(regex("^8\\.0\\.[0-9]+$", var.rds_engine_version)))
    )
    error_message = "Legacy services require MySQL 8.0.x; explicit B data-only preparation requires 8.4.11."
  }
}

variable "global_b_services" {
  description = "Explicit MySQL 8.4.11/V28 normal-service contract; separate from the legacy V27 experiment."
  type        = bool
  default     = false
  validation {
    condition = !var.global_b_services || (
      !var.global_b_prepare_only && !var.load_generator_enabled && var.mode == "performance" &&
      var.dns_mode == "direct-only" && (var.database_bootstrap == "dump" || (var.database_bootstrap == "snapshot" && var.global_b_snapshot_provenance != null)) &&
      can(regex("^global-growth-b-[0-9a-f]{16}$", var.dataset_release)) &&
      can(regex("^[a-z0-9][a-z0-9-]{2,47}$", var.global_b_service_release)) &&
      can(regex("^[A-Za-z0-9._~+/=-]+$", var.global_b_manifest_version_id)) &&
      !contains(["null", "None"], var.global_b_manifest_version_id) &&
      can(regex("^[A-Za-z0-9._:@/-]{3,128}$", var.global_b_lease_owner))
    )
    error_message = "B services require a separate immutable B service release, MySQL 8.4.11, dump/direct-only/performance, live lease owner, and no load generator. B snapshot restoration requires its separate provenance contract."
  }
}

variable "global_b_service_release" {
  description = "Immutable sibling aws-service release name. No payloads are added to the finite SQL release."
  type        = string
  default     = ""
}

variable "global_b_service_bootstrap_enabled" {
  description = "Run the explicit B CDC/readiness bootstrap after the actual native S3 restore receipt has been published."
  type        = bool
  default     = false
  validation {
    condition     = !var.global_b_service_bootstrap_enabled || (var.global_b_services && !var.app_enabled && var.deployment_phase == "services")
    error_message = "B bootstrap runs only with services selected and the application ASG held at zero."
  }
}

variable "global_b_readiness_receipt" {
  description = "Pinned immutable B dependency receipt; required before enabling application capacity."
  type        = object({ key = string, version_id = string, sha256 = string, bytes = number })
  default     = null
}

variable "global_b_lease_fencing_token" {
  description = "Current B service operation lease token; retained resources keep their original fencing_token tags."
  type        = number
  default     = 0
  validation {
    condition     = !var.global_b_services || (var.global_b_lease_fencing_token > 0 && floor(var.global_b_lease_fencing_token) == var.global_b_lease_fencing_token)
    error_message = "B service continuation must bind its current operation lease separately from the retained resource fence."
  }
}
