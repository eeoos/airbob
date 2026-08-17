variable "account_id" {
  description = "AWS account that owns the Airbob performance-lab state."
  type        = string
  default     = "942632789808"

  validation {
    condition     = var.account_id == "942632789808"
    error_message = "The bootstrap may only target AWS account 942632789808."
  }
}

variable "aws_region" {
  description = "AWS region for the state bucket."
  type        = string
  default     = "ap-northeast-2"

  validation {
    condition     = var.aws_region == "ap-northeast-2"
    error_message = "The bootstrap may only target ap-northeast-2."
  }
}

variable "state_bucket_name" {
  description = "Deterministic, account-qualified S3 bucket name for Terraform state."
  type        = string
  default     = "airbob-performance-lab-tfstate-942632789808"

  validation {
    condition     = var.state_bucket_name == "airbob-performance-lab-tfstate-942632789808"
    error_message = "The state bucket name must be airbob-performance-lab-tfstate-942632789808."
  }
}
