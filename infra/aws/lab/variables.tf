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
      ? can(regex("^db-[A-Z0-9]{24}$", var.rds_snapshot_source_resource_id))
      : var.rds_snapshot_source_resource_id == ""
    )
    error_message = "snapshot bootstrap requires the exact db-* source RDS resource ID; every other bootstrap mode requires it to be empty."
  }
}

variable "rds_engine_version" {
  description = "Exact reviewed RDS MySQL 8.0 patch version recorded with each run."
  type        = string
  default     = ""

  validation {
    condition = (
      !contains(["services", "data-ready"], var.deployment_phase) ||
      can(regex("^8\\.0\\.[0-9]+$", var.rds_engine_version))
    )
    error_message = "services and data-ready require an exact MySQL 8.0 patch version."
  }
}
