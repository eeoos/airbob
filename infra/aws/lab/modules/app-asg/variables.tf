variable "name_prefix" {
  type = string
}

variable "ami_id" {
  type = string
}

variable "instance_type" {
  type = string

  validation {
    condition     = var.instance_type == "c6i.large"
    error_message = "The measured application ASG must use c6i.large."
  }
}

variable "subnet_ids" {
  type = list(string)

  validation {
    condition     = contains([1, 2], length(var.subnet_ids))
    error_message = "The app ASG must use one subnet in performance mode or two in scaling mode."
  }
}

variable "security_group_ids" {
  type = list(string)
}

variable "instance_profile_name" {
  type = string
}

variable "user_data" {
  type = string
}

variable "runtime_revision" {
  type = string

  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.runtime_revision))
    error_message = "runtime_revision must be a SHA-256 digest."
  }
}

variable "mode" {
  type = string

  validation {
    condition     = contains(["performance", "scaling"], var.mode)
    error_message = "mode must be performance or scaling."
  }
}

variable "app_enabled" {
  type = bool
}

variable "min_size" {
  type = number
}

variable "desired_capacity" {
  type = number
}

variable "max_size" {
  type = number
}

variable "target_group_arns" {
  type = list(string)
}

variable "refresh_alarm_names" {
  type = list(string)
}

variable "scaling_enabled" {
  type = bool
}

variable "request_count_per_target_per_minute" {
  type    = number
  default = null
}

variable "alb_resource_label" {
  type = string
}

variable "tags" {
  type = map(string)
}
