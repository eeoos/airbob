variable "account_id" {
  description = "AWS account that may contain ephemeral Airbob lab resources."
  type        = string
  default     = "942632789808"

  validation {
    condition     = var.account_id == "942632789808"
    error_message = "The lab state may only target AWS account 942632789808."
  }
}

variable "aws_region" {
  description = "AWS region for the Airbob performance lab."
  type        = string
  default     = "ap-northeast-2"

  validation {
    condition     = var.aws_region == "ap-northeast-2"
    error_message = "The lab state may only target ap-northeast-2."
  }
}
