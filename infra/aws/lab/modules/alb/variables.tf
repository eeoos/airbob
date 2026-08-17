variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)

  validation {
    condition     = length(var.public_subnet_ids) == 2
    error_message = "The internet-facing ALB must span exactly two public subnets."
  }
}

variable "security_group_id" {
  type = string
}

variable "certificate_arn" {
  type = string
}

variable "tags" {
  type = map(string)
}
