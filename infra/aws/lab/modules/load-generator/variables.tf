variable "name_prefix" {
  type = string
}

variable "ami_id" {
  type = string
}

variable "subnet_id" {
  type = string
}

variable "security_group_id" {
  type = string
}

variable "instance_profile_name" {
  type = string
}

variable "detailed_monitoring" {
  type        = bool
  default     = true
  description = "Enable one-minute EC2 metrics when the operator role has MonitorInstances permission."
}

variable "user_data" {
  type = string
}

variable "tags" {
  type = map(string)
}
