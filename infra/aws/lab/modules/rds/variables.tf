variable "name" {
  type = string
}

variable "engine_version" {
  type = string
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
