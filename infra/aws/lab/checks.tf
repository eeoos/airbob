locals {
  network_receipt = try(
    jsondecode(nonsensitive(data.aws_s3_object.network_receipt[0].body)),
    null,
  )
  network_receipt_valid = !local.receipt_required || try(
    toset(keys(local.network_receipt)) == toset([
      "schemaVersion",
      "runId",
      "vpcId",
      "primaryRouteTableId",
      "probeInstanceId",
      "amiId",
      "s3Gateway",
      "ecrApi",
      "ssmApi",
      "secretsManagerApi",
      "verifiedAt",
    ]) &&
    local.network_receipt.schemaVersion == 1 &&
    local.network_receipt.runId == var.run_id &&
    local.network_receipt.vpcId == module.network.vpc_id &&
    local.network_receipt.primaryRouteTableId == module.network.private_route_table_ids.primary &&
    local.network_receipt.probeInstanceId == var.verified_probe_instance_id &&
    local.network_receipt.amiId == var.ami_id &&
    local.network_receipt.s3Gateway == "verified" &&
    local.network_receipt.ecrApi == "verified" &&
    local.network_receipt.ssmApi == "verified" &&
    local.network_receipt.secretsManagerApi == "verified" &&
    can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", local.network_receipt.verifiedAt)),
    false,
  )

  probe_clearance_receipt = try(
    jsondecode(nonsensitive(data.aws_s3_object.probe_clearance_receipt[0].body)),
    null,
  )
  probe_clearance_valid = !local.services_enabled || try(
    toset(keys(local.probe_clearance_receipt)) == toset([
      "schemaVersion",
      "runId",
      "vpcId",
      "probeInstanceId",
      "instanceState",
      "clearedAt",
    ]) &&
    local.probe_clearance_receipt.schemaVersion == 1 &&
    local.probe_clearance_receipt.runId == var.run_id &&
    local.probe_clearance_receipt.vpcId == module.network.vpc_id &&
    local.probe_clearance_receipt.probeInstanceId == var.verified_probe_instance_id &&
    local.probe_clearance_receipt.instanceState == "terminated" &&
    can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", local.probe_clearance_receipt.clearedAt)),
    false,
  )

  bundle_manifest = try(
    jsondecode(nonsensitive(data.aws_s3_object.bundle_manifest[0].body)),
    null,
  )
  bundle_checksum_line = local.services_enabled ? trimspace(try(
    nonsensitive(data.aws_s3_object.bundle_checksum[0].body),
    "",
  )) : ""
  bundle_release_valid = !local.services_enabled || try(
    local.bundle_manifest.schemaVersion == 1 &&
    local.bundle_manifest.commit == var.bundle_commit &&
    local.bundle_manifest.archive == local.bundle_archive_name &&
    local.bundle_manifest.sha256 == var.bundle_sha256 &&
    local.bundle_manifest.files == local.service_bundle_files &&
    local.bundle_checksum_line == "${var.bundle_sha256}  ${local.bundle_archive_name}",
    false,
  )
}

check "foundation_boundary" {
  assert {
    condition = (
      local.caller_identity_valid &&
      local.contract_schema_valid &&
      local.state_boundaries_valid &&
      local.operational_contract_valid &&
      local.ecr_contract_valid
    )
    error_message = "Phase 2 requires the exact validated foundation contract and pinned caller boundary."
  }
}

check "phase_transition" {
  assert {
    condition     = local.network_receipt_valid && local.probe_clearance_valid
    error_message = "Later phases require exact egress and probe-termination receipts for this VPC, run, AMI, and probe."
  }
}

resource "terraform_data" "network_receipt_gate" {
  count = local.receipt_required ? 1 : 0

  input = var.verified_probe_instance_id

  lifecycle {
    precondition {
      condition     = local.network_receipt_valid
      error_message = "Refusing to remove the probe or create services without the exact network egress receipt."
    }
  }
}

resource "terraform_data" "probe_clearance_gate" {
  count = local.services_enabled ? 1 : 0

  input = var.verified_probe_instance_id

  lifecycle {
    precondition {
      condition     = local.probe_clearance_valid
      error_message = "Refusing to create services until the verified probe is confirmed terminated after a probe-cleared apply."
    }
  }
}

resource "terraform_data" "service_release_gate" {
  count = local.services_enabled ? 1 : 0

  input = var.bundle_commit

  lifecycle {
    precondition {
      condition     = local.bundle_release_valid && local.phase2_images_valid
      error_message = "Refusing to create services without the exact immutable bundle and image digest release."
    }
  }
}

check "service_release" {
  assert {
    condition     = local.bundle_release_valid && local.phase2_images_valid
    error_message = "The services phase requires an immutable nineteen-file bundle release and nine exact ECR digest references."
  }
}
