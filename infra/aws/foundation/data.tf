data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

data "aws_s3_bucket" "application" {
  bucket = var.existing_application_bucket_name
}

check "foundation_target" {
  assert {
    condition = (
      data.aws_caller_identity.current.account_id == var.account_id &&
      data.aws_region.current.region == var.aws_region
    )
    error_message = "The active AWS caller must target the canonical Airbob account and Seoul region."
  }
}

check "github_oidc_subject_mode_consistency" {
  assert {
    condition     = local.github_subjects_are_legacy || local.github_subjects_are_immutable
    error_message = "All GitHub OIDC subjects must use the same reviewed legacy or immutable repository identity format."
  }
}
