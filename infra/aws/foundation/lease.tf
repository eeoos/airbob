resource "aws_dynamodb_table" "orchestration_lease" {
  name                        = "airbob-performance-lab-orchestration-lease"
  billing_mode                = "PAY_PER_REQUEST"
  hash_key                    = local.lease_partition_key
  deletion_protection_enabled = true

  attribute {
    name = local.lease_partition_key
    type = "S"
  }

  server_side_encryption {
    enabled = true
  }

  point_in_time_recovery {
    enabled = true
  }

  lifecycle {
    prevent_destroy = true
  }
}
