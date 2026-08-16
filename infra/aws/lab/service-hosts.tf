locals {
  phase2_images_env = local.services_enabled ? join("\n", [
    for image_key in sort(tolist(local.phase2_image_keys)) :
    "${image_key}=${lookup(var.infra_image_references, image_key, "")}"
  ]) : ""

  service_host_inputs = local.services_enabled ? {
    for service, host in local.service_hosts : service => {
      instance_type         = host.instance_type
      volume_size           = host.volume_size
      subnet_id             = module.network.private_subnet_ids.primary
      security_group_ids    = [module.security.security_group_ids[service]]
      instance_profile_name = aws_iam_instance_profile.host[service].name
      user_data = templatefile("${path.module}/templates/host-user-data.sh.tftpl", {
        mode                   = "service"
        service                = service
        region                 = var.aws_region
        account_id             = var.account_id
        bundle_bucket          = local.lab_contract.bundle_bucket_name
        bundle_archive_key     = local.bundle_archive_key
        bundle_checksum_key    = local.bundle_checksum_key
        bundle_manifest_key    = local.bundle_manifest_key
        bundle_commit          = var.bundle_commit
        bundle_sha256          = var.bundle_sha256
        bundle_files_json      = jsonencode(local.service_bundle_files)
        images_env             = local.phase2_images_env
        docker_compose_version = local.docker_compose_version
        docker_compose_sha256  = local.docker_compose_sha256
      })
      monitoring = true
    }
  } : {}
}

module "service_hosts" {
  source = "./modules/service-ec2"

  name_prefix = "airbob-${var.run_id}"
  ami_id      = data.aws_ami.selected.id
  hosts       = local.service_host_inputs
  tags        = local.ephemeral_tags

  depends_on = [
    terraform_data.network_receipt_gate,
    terraform_data.probe_clearance_gate,
    terraform_data.service_release_gate,
    aws_route.private_nat,
  ]
}
