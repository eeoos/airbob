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

variable "user_data" {
  type = string
}

variable "tags" {
  type = map(string)
}
