variable "account_id" {
  description = "AWS account that owns the Airbob performance-lab foundation."
  type        = string
  default     = "942632789808"

  validation {
    condition     = var.account_id == "942632789808"
    error_message = "The foundation may only target AWS account 942632789808."
  }
}

variable "aws_region" {
  description = "AWS region for the Airbob performance lab."
  type        = string
  default     = "ap-northeast-2"

  validation {
    condition     = var.aws_region == "ap-northeast-2"
    error_message = "The foundation may only target ap-northeast-2."
  }
}

variable "state_bucket_name" {
  description = "Existing persistent Terraform state bucket created by bootstrap."
  type        = string
  default     = "airbob-performance-lab-tfstate-942632789808"

  validation {
    condition     = var.state_bucket_name == "airbob-performance-lab-tfstate-942632789808"
    error_message = "The state bucket name must match the bootstrap contract."
  }
}

variable "dataset_bucket_name" {
  description = "Globally unique bucket for immutable performance-lab dataset releases."
  type        = string
  default     = "airbob-performance-lab-dataset-942632789808"

  validation {
    condition     = var.dataset_bucket_name == "airbob-performance-lab-dataset-942632789808"
    error_message = "The dataset bucket name must remain deterministic and account-qualified."
  }
}

variable "evidence_bucket_name" {
  description = "Globally unique bucket for tagged performance evidence."
  type        = string
  default     = "airbob-performance-lab-evidence-942632789808"

  validation {
    condition     = var.evidence_bucket_name == "airbob-performance-lab-evidence-942632789808"
    error_message = "The evidence bucket name must remain deterministic and account-qualified."
  }
}

variable "bundle_bucket_name" {
  description = "Globally unique bucket for immutable service-bundle releases."
  type        = string
  default     = "airbob-performance-lab-bundles-942632789808"

  validation {
    condition     = var.bundle_bucket_name == "airbob-performance-lab-bundles-942632789808"
    error_message = "The bundle bucket name must remain deterministic and account-qualified."
  }
}

variable "existing_application_bucket_name" {
  description = "Existing application object bucket. Foundation reads its identity but never manages it."
  type        = string

  validation {
    condition = (
      can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.existing_application_bucket_name)) &&
      !contains([
        var.state_bucket_name,
        var.dataset_bucket_name,
        var.evidence_bucket_name,
        var.bundle_bucket_name,
      ], var.existing_application_bucket_name)
    )
    error_message = "The application bucket must be a distinct, valid existing S3 bucket name."
  }
}

variable "static_dns_records" {
  description = "Reviewed Gabia record inventory excluding NS, SOA, api origin records, and ACM validation records."
  type = map(object({
    name    = string
    type    = string
    ttl     = number
    records = list(string)
  }))

  validation {
    condition = (
      length(var.static_dns_records) > 0 &&
      length(toset([
        for record in values(var.static_dns_records) : "${lower(record.name)}|${upper(record.type)}"
      ])) == length(var.static_dns_records)
    )
    error_message = "Static DNS inventory must be non-empty and contain each case-insensitive name/type pair exactly once."
  }

  validation {
    condition = alltrue([
      for record in values(var.static_dns_records) :
      record.name == trimsuffix(record.name, ".") &&
      (lower(record.name) == "airbob.cloud" || endswith(lower(record.name), ".airbob.cloud")) &&
      lower(record.name) != "api.airbob.cloud"
    ])
    error_message = "Static DNS names must be relative to airbob.cloud without a trailing dot and must exclude api.airbob.cloud."
  }

  validation {
    condition = alltrue([
      for record in values(var.static_dns_records) :
      contains(["A", "AAAA", "CAA", "CNAME", "MX", "TXT"], upper(record.type)) &&
      record.ttl >= 1 && record.ttl <= 86400 &&
      length(record.records) > 0 &&
      alltrue([for value in record.records : trimspace(value) != ""])
    ])
    error_message = "Static DNS records must use an approved type, TTL 1..86400, and at least one nonblank value."
  }

  validation {
    condition = alltrue([
      for record in values(var.static_dns_records) :
      !(upper(record.type) == "CNAME" && lower(record.name) == "airbob.cloud") &&
      !(upper(record.type) == "CNAME" && anytrue([
        for value in record.records : endswith(lower(trimsuffix(value, ".")), ".acm-validations.aws")
      ])) &&
      (upper(record.type) != "CNAME" || length([
        for candidate in values(var.static_dns_records) : candidate
        if lower(candidate.name) == lower(record.name)
      ]) == 1)
    ])
    error_message = "Static CNAME records must exclude the apex and ACM validation targets and cannot coexist with another type at the same name."
  }
}

variable "dns_inventory_reviewed" {
  description = "Explicit acknowledgement that the full Gabia zone inventory was reviewed."
  type        = bool
  default     = false

  validation {
    condition     = var.dns_inventory_reviewed
    error_message = "Set dns_inventory_reviewed only after reviewing the complete Gabia zone inventory."
  }
}

variable "dnssec_ds_reviewed" {
  description = "Explicit acknowledgement that Gabia DNSSEC/DS state was checked before delegation."
  type        = bool
  default     = false

  validation {
    condition     = var.dnssec_ds_reviewed
    error_message = "Set dnssec_ds_reviewed only after checking the registrar DS/DNSSEC state."
  }
}

variable "dns_delegation_confirmed" {
  description = "Whether Gabia delegates airbob.cloud to the Route 53 name servers from this foundation."
  type        = bool
  default     = false
}

variable "github_foundation_subject" {
  description = "Exact GitHub OIDC subject observed for the protected aws-foundation environment."
  type        = string

  validation {
    condition = contains([
      "repo:eeoos/airbob:environment:aws-foundation",
      "repo:eeoos@119295425/airbob@1056501820:environment:aws-foundation",
    ], var.github_foundation_subject)
    error_message = "github_foundation_subject must be the exact reviewed legacy or immutable Airbob foundation subject."
  }
}

variable "github_lab_subject" {
  description = "Exact GitHub OIDC subject observed for the protected aws-performance-lab environment."
  type        = string

  validation {
    condition = contains([
      "repo:eeoos/airbob:environment:aws-performance-lab",
      "repo:eeoos@119295425/airbob@1056501820:environment:aws-performance-lab",
    ], var.github_lab_subject)
    error_message = "github_lab_subject must be the exact reviewed legacy or immutable Airbob lab subject."
  }
}

variable "github_image_subject" {
  description = "Exact GitHub OIDC subject observed for image publishing from main."
  type        = string

  validation {
    condition = contains([
      "repo:eeoos/airbob:ref:refs/heads/main",
      "repo:eeoos@119295425/airbob@1056501820:ref:refs/heads/main",
    ], var.github_image_subject)
    error_message = "github_image_subject must be the exact reviewed legacy or immutable Airbob main-branch subject."
  }
}

variable "github_oidc_subjects_reviewed" {
  description = "Explicit acknowledgement that all three exact subjects were checked against the repository OIDC configuration."
  type        = bool
  default     = false

  validation {
    condition     = var.github_oidc_subjects_reviewed
    error_message = "Set github_oidc_subjects_reviewed only after checking the exact GitHub OIDC subjects."
  }
}

variable "local_principal_arns" {
  description = "Exact local IAM principal ARNs allowed to assume the foundation and lab roles."
  type        = set(string)

  validation {
    condition = (
      length(var.local_principal_arns) > 0 &&
      alltrue([
        for arn in var.local_principal_arns :
        can(regex("^arn:aws:iam::942632789808:(user|role)/[A-Za-z0-9+=,.@_/-]+$", arn))
      ])
    )
    error_message = "Provide at least one exact IAM user or role ARN from account 942632789808."
  }
}

variable "local_principal_requires_mfa" {
  description = "Whether local STS AssumeRole calls must present MFA. This must be an explicit operator decision."
  type        = bool
}

variable "application_ecr_scan_on_push" {
  description = "Observed live scan-on-push value for airbob-repo; set it explicitly so import preserves the reviewed state."
  type        = bool
}

variable "ecr_tagged_image_count" {
  description = "Number of recent tagged images retained per managed ECR repository."
  type        = number
  default     = 50

  validation {
    condition     = var.ecr_tagged_image_count >= 20 && var.ecr_tagged_image_count <= 500
    error_message = "ecr_tagged_image_count must be between 20 and 500."
  }
}

variable "expiry_observer_enabled" {
  description = "Whether the read-only expiry observer schedule and alarm actions are active. Cleanup remains unavailable."
  type        = bool
  default     = false
}

variable "expiry_alert_email" {
  description = "Email endpoint for expiry observer alarms. Confirm the SNS subscription before enabling the observer."
  type        = string
  default     = null
  nullable    = true

  validation {
    condition = (
      var.expiry_alert_email == null ||
      (
        trimspace(var.expiry_alert_email) == var.expiry_alert_email &&
        can(regex("^[^@[:space:]]+@[^@[:space:]]+\\.[^@[:space:]]+$", var.expiry_alert_email))
      )
    )
    error_message = "expiry_alert_email must be null or one canonical email address without whitespace."
  }
}

variable "expiry_alert_subscription_confirmed" {
  description = "Explicit acknowledgement that the configured SNS email subscription was confirmed before observer activation."
  type        = bool
  default     = false
}
