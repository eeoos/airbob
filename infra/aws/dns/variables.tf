variable "account_id" {
  description = "AWS account that owns the Airbob performance-lab DNS state."
  type        = string
  default     = "942632789808"

  validation {
    condition     = var.account_id == "942632789808"
    error_message = "The DNS state may only target AWS account 942632789808."
  }
}

variable "aws_region" {
  description = "AWS region for the Airbob performance lab."
  type        = string
  default     = "ap-northeast-2"

  validation {
    condition     = var.aws_region == "ap-northeast-2"
    error_message = "The DNS state may only target ap-northeast-2."
  }
}

variable "oci_origin_ipv4" {
  description = "Reviewed public IPv4 address of the always-on OCI API origin."
  type        = string

  validation {
    condition = (
      trimspace(var.oci_origin_ipv4) == var.oci_origin_ipv4 &&
      try(cidrhost("${var.oci_origin_ipv4}/32", 0) == var.oci_origin_ipv4, false)
    )
    error_message = "oci_origin_ipv4 must be one canonical IPv4 address without CIDR notation or whitespace."
  }
}

variable "aws_alb_arn" {
  description = "Optional same-account Seoul application load-balancer ARN, supplied only after the lab is healthy."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = (
      var.aws_alb_arn == null ||
      (
        trimspace(var.aws_alb_arn) == var.aws_alb_arn &&
        can(regex(
          "^arn:aws:elasticloadbalancing:ap-northeast-2:942632789808:loadbalancer/app/[A-Za-z0-9-]+/[0-9a-f]+$",
          var.aws_alb_arn,
        ))
      )
    )
    error_message = "aws_alb_arn must identify an application load balancer in the pinned account and Seoul region."
  }
}
