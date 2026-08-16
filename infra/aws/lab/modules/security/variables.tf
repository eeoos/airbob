variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_cidrs" {
  type = map(string)
}

variable "tags" {
  type = map(string)
}
