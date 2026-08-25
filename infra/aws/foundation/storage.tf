locals {
  managed_buckets = {
    dataset  = var.dataset_bucket_name
    evidence = var.evidence_bucket_name
    bundle   = var.bundle_bucket_name
  }
}

resource "aws_s3_bucket" "managed" {
  for_each = local.managed_buckets

  bucket        = each.value
  force_destroy = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "managed" {
  for_each = aws_s3_bucket.managed

  bucket = each.value.id

  versioning_configuration {
    status = "Enabled"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "managed" {
  for_each = aws_s3_bucket.managed

  bucket = each.value.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "managed" {
  for_each = aws_s3_bucket.managed

  bucket                  = each.value.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_ownership_controls" "managed" {
  for_each = aws_s3_bucket.managed

  bucket = each.value.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_policy" "managed" {
  for_each = aws_s3_bucket.managed

  bucket = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [{
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          each.value.arn,
          "${each.value.arn}/*",
        ]
        Condition = {
          Bool = {
            "aws:SecureTransport" = "false"
          }
        }
      }],
      [for statement in [
        {
          Sid       = "DenyDatasetReleaseOverwrite"
          Effect    = "Deny"
          Principal = "*"
          Action    = "s3:PutObject"
          Resource  = "${each.value.arn}/datasets/*"
          Condition = {
            Null = {
              "s3:if-none-match" = "true"
            }
            Bool = {
              "s3:ObjectCreationOperation" = "true"
            }
          }
        },
        {
          Sid       = "DenyDatasetReleaseDeletion"
          Effect    = "Deny"
          Principal = "*"
          Action    = ["s3:DeleteObject", "s3:DeleteObjectVersion"]
          Resource  = "${each.value.arn}/datasets/*"
        },
        {
          Sid       = "DenySnapshotSealOverwrite"
          Effect    = "Deny"
          Principal = "*"
          Action    = "s3:PutObject"
          Resource  = "${each.value.arn}/elasticsearch/seals/*"
          Condition = {
            Null = {
              "s3:if-none-match" = "true"
            }
            Bool = {
              "s3:ObjectCreationOperation" = "true"
            }
          }
        },
        {
          Sid       = "DenySnapshotSealDeletion"
          Effect    = "Deny"
          Principal = "*"
          Action    = ["s3:DeleteObject", "s3:DeleteObjectVersion"]
          Resource  = "${each.value.arn}/elasticsearch/seals/*"
        },
      ] : statement if each.key == "dataset"],
    )
  })

  depends_on = [aws_s3_bucket_public_access_block.managed]

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "release" {
  for_each = toset(["dataset", "bundle"])

  bucket = aws_s3_bucket.managed[each.value].id

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  # A snapshot seal binds every completed object version and delete marker in
  # its release prefix. Only the non-snapshot bundle bucket may expire old
  # versions; incomplete multipart uploads never become sealed object versions.
  dynamic "rule" {
    for_each = each.value == "bundle" ? [true] : []

    content {
      id     = "expire-noncurrent-versions"
      status = "Enabled"

      filter {}

      noncurrent_version_expiration {
        noncurrent_days = 30
      }
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "evidence" {
  bucket = aws_s3_bucket.managed["evidence"].id

  rule {
    id     = "expire-tagged-raw-evidence"
    status = "Enabled"

    filter {
      tag {
        key   = "Retention"
        value = "raw"
      }
    }

    expiration {
      days = 30
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  rule {
    id     = "expire-tagged-summary-evidence"
    status = "Enabled"

    filter {
      tag {
        key   = "Retention"
        value = "summary"
      }
    }

    expiration {
      days = 365
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }

  rule {
    id     = "abort-incomplete-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}
