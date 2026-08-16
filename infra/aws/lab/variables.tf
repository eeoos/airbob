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
    condition     = can(regex("^[a-z0-9][a-z0-9-]{2,31}$", var.run_id))
    error_message = "run_id must be 3-32 lower-case letters, digits, or hyphens and start with an alphanumeric character."
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

variable "deployment_phase" {
  description = "Ordered Phase 2 transition; services are forbidden until a verified probe has been removed."
  type        = string
  default     = "network"

  validation {
    condition     = contains(["network", "probe-cleared", "services"], var.deployment_phase)
    error_message = "deployment_phase must be network, probe-cleared, or services."
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
  description = "Second AZ reserved for later scaling mode."
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
      var.deployment_phase != "services" ||
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
      var.deployment_phase != "services" ||
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
