variable "name_prefix" {
  type = string
}

variable "vpc_id" {
  type = string
}

variable "private_subnet_cidrs" {
  type = map(string)
}

variable "dns_mode" {
  type = string

  validation {
    condition     = contains(["direct-only", "cutover"], var.dns_mode)
    error_message = "dns_mode must be direct-only or cutover."
  }
}

variable "alb_ingress_cidr" {
  type = string

  validation {
    condition = (
      (var.dns_mode == "direct-only" && endswith(var.alb_ingress_cidr, "/32") && var.alb_ingress_cidr != "0.0.0.0/0") ||
      (var.dns_mode == "cutover" && var.alb_ingress_cidr == "0.0.0.0/0")
    )
    error_message = "direct-only requires host-scoped HTTPS ingress and cutover requires public HTTPS ingress."
  }
}

variable "tags" {
  type = map(string)
}
