variable "name" {
  type = string
}

variable "engine_version" {
  type = string

  validation {
    condition     = can(regex("^8\\.(0|4)\\.[0-9]+$", var.engine_version))
    error_message = "engine_version must be an explicit MySQL 8.0.x or 8.4.x patch version."
  }
}

variable "bootstrap_mode" {
  type = string

  validation {
    condition     = contains(["dump", "snapshot"], var.bootstrap_mode)
    error_message = "bootstrap_mode must be dump or snapshot."
  }
}

variable "dump_storage_gib" {
  type = number

  validation {
    condition     = contains([20, 100], var.dump_storage_gib)
    error_message = "dump_storage_gib must be one of the closed dataset-profile capacities: 20 or 100 GiB."
  }
}

variable "snapshot_identifier" {
  type    = string
  default = null
}

variable "subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  type = string
}

variable "availability_zone" {
  type = string
}

variable "tags" {
  type = map(string)
}
variable "instance_class" {
  description = "Reviewed initial RDS class; retained runs never change it."
  type        = string
  default     = "db.t3.small"
  validation {
    condition = var.instance_class == "db.t3.small" || (
      var.instance_class == "db.m6i.large" && var.engine_version == "8.4.11" &&
      var.dump_storage_gib == 100 && try(var.tags.BDatabaseClass, "") == var.instance_class
    )
    error_message = "Only explicit Global B db.m6i.large with MySQL 8.4.11/100GiB/class tag is additionally allowed."
  }
}
