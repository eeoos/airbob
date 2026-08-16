mock_provider "aws" {}

run "state_bucket_contract" {
  command = plan

  assert {
    condition     = aws_s3_bucket.state.bucket == "airbob-performance-lab-tfstate-942632789808"
    error_message = "The state bucket name must remain deterministic and account-qualified."
  }

  assert {
    condition = (
      aws_s3_bucket.state.tags["Project"] == "airbob" &&
      aws_s3_bucket.state.tags["Environment"] == "performance-lab" &&
      aws_s3_bucket.state.tags["ManagedBy"] == "terraform" &&
      aws_s3_bucket.state.tags["Persistence"] == "persistent"
    )
    error_message = "The state bucket must carry the complete persistence tag contract."
  }

  assert {
    condition     = aws_s3_bucket_versioning.state.versioning_configuration[0].status == "Enabled"
    error_message = "The state bucket must keep versioning enabled."
  }

  assert {
    condition     = one(aws_s3_bucket_server_side_encryption_configuration.state.rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256"
    error_message = "The state bucket must use the documented SSE-S3 encryption choice."
  }

  assert {
    condition = (
      aws_s3_bucket_public_access_block.state.block_public_acls &&
      aws_s3_bucket_public_access_block.state.block_public_policy &&
      aws_s3_bucket_public_access_block.state.ignore_public_acls &&
      aws_s3_bucket_public_access_block.state.restrict_public_buckets
    )
    error_message = "Every S3 public-access block setting must remain enabled."
  }

  assert {
    condition     = one(aws_s3_bucket_ownership_controls.state.rule).object_ownership == "BucketOwnerEnforced"
    error_message = "The state bucket must keep ACLs disabled with bucket-owner enforcement."
  }
}

run "reject_wrong_account" {
  command = plan

  variables {
    account_id = "111111111111"
  }

  expect_failures = [var.account_id]
}

run "reject_wrong_region" {
  command = plan

  variables {
    aws_region = "us-east-1"
  }

  expect_failures = [var.aws_region]
}

run "reject_noncanonical_bucket" {
  command = plan

  variables {
    state_bucket_name = "some-other-bucket"
  }

  expect_failures = [var.state_bucket_name]
}
