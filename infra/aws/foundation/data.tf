data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

data "aws_s3_bucket" "application" {
  bucket = var.existing_application_bucket_name
}

data "aws_s3_objects" "dataset_snapshot_seal_plan" {
  count = var.dataset_snapshot_writer_release == null ? 0 : 1

  bucket   = aws_s3_bucket.managed["dataset"].id
  prefix   = local.dataset_snapshot_seal_key
  max_keys = 1

  lifecycle {
    postcondition {
      condition = !contains(
        self.keys,
        local.dataset_snapshot_seal_key,
      )
      error_message = "A sealed dataset release can never regain Elasticsearch snapshot writer permissions."
    }
  }
}

# timestamp() stays unknown in a saved plan. Passing it through terraform_data
# deliberately defers the second lookup until apply, closing the gap where an
# immutable snapshot seal could appear after plan-time validation.
resource "terraform_data" "dataset_snapshot_activation" {
  count = var.dataset_snapshot_writer_release == null ? 0 : 1

  input = {
    release     = var.dataset_snapshot_writer_release
    apply_nonce = timestamp()
  }
}

data "aws_s3_objects" "dataset_snapshot_seal_apply" {
  count = var.dataset_snapshot_writer_release == null ? 0 : 1

  bucket   = aws_s3_bucket.managed["dataset"].id
  prefix   = local.dataset_snapshot_seal_key
  max_keys = 1

  depends_on = [terraform_data.dataset_snapshot_activation]

  lifecycle {
    postcondition {
      condition = !contains(
        self.keys,
        local.dataset_snapshot_seal_key,
      )
      error_message = "A dataset release sealed during apply can never regain Elasticsearch snapshot writer permissions."
    }
  }
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

check "local_principal_role_separation" {
  assert {
    condition = (
      length(setintersection(
        var.foundation_local_principal_arns,
        var.lab_local_principal_arns,
      )) == 0 &&
      length(setintersection(
        var.dataset_publisher_local_principal_arns,
        var.lab_local_principal_arns,
      )) == 0
    )
    error_message = "Foundation/dataset-publisher principals must remain disjoint from lab local principals."
  }
}
