data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_ssm_parameter" "foundation_contract" {
  name            = "/airbob/performance-lab/foundation/lab-contract"
  with_decryption = false
}

data "aws_ami" "selected" {
  filter {
    name   = "image-id"
    values = [var.ami_id]
  }

  filter {
    name   = "architecture"
    values = ["x86_64"]
  }

  filter {
    name   = "state"
    values = ["available"]
  }

  owners = ["137112412989"]
}

data "aws_s3_object" "network_receipt" {
  count = local.receipt_required ? 1 : 0

  bucket = local.lab_contract.evidence_bucket_name
  key    = "network-receipts/${var.run_id}/${var.verified_probe_instance_id}.json"
}

data "aws_s3_object" "probe_clearance_receipt" {
  count = local.services_enabled ? 1 : 0

  bucket = local.lab_contract.evidence_bucket_name
  key    = "network-clearance/${var.run_id}/${var.verified_probe_instance_id}.json"
}

data "aws_s3_object" "bundle_manifest" {
  count = local.services_enabled ? 1 : 0

  bucket = local.lab_contract.bundle_bucket_name
  key    = local.bundle_manifest_key
}

data "aws_s3_object" "bundle_checksum" {
  count = local.services_enabled ? 1 : 0

  bucket = local.lab_contract.bundle_bucket_name
  key    = local.bundle_checksum_key
}
