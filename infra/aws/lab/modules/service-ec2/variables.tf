variable "name_prefix" {
  type = string
}

variable "ami_id" {
  type = string
}

variable "hosts" {
  type = map(object({
    instance_type         = string
    volume_size           = number
    subnet_id             = string
    security_group_ids    = list(string)
    instance_profile_name = string
    user_data             = string
    monitoring            = bool
  }))
}

variable "tags" {
  type = map(string)
}
