module "network" {
  source = "./modules/network"

  name_prefix          = "airbob-${var.run_id}"
  vpc_cidr             = local.network_cidr
  availability_zones   = local.availability_zones
  public_subnet_cidrs  = local.public_subnet_cidrs
  private_subnet_cidrs = local.private_subnet_cidrs
  tags                 = local.ephemeral_tags
}

module "nat" {
  source = "./modules/nat-instance"

  name_prefix           = "airbob-${var.run_id}"
  ami_id                = data.aws_ami.selected.id
  subnet_id             = module.network.public_subnet_ids.primary
  security_group_id     = module.security.security_group_ids.nat
  instance_profile_name = aws_iam_instance_profile.host["nat"].name
  user_data = templatefile("${path.module}/templates/nat-user-data.sh.tftpl", {
    region = var.aws_region
  })
  tags = local.ephemeral_tags
}

resource "aws_route" "private_nat" {
  for_each = module.network.private_route_table_ids

  route_table_id         = each.value
  destination_cidr_block = "0.0.0.0/0"
  network_interface_id   = module.nat.primary_network_interface_id
}

module "egress_probe" {
  source = "./modules/service-ec2"

  name_prefix = "airbob-${var.run_id}"
  ami_id      = data.aws_ami.selected.id
  hosts = local.probe_enabled ? {
    egress-probe = {
      instance_type         = "t3.nano"
      volume_size           = 8
      subnet_id             = module.network.private_subnet_ids.primary
      security_group_ids    = [module.security.security_group_ids.probe]
      instance_profile_name = aws_iam_instance_profile.host["probe"].name
      user_data = templatefile("${path.module}/templates/host-user-data.sh.tftpl", {
        mode                   = "probe"
        service                = "egress-probe"
        region                 = var.aws_region
        account_id             = var.account_id
        bundle_bucket          = ""
        bundle_archive_key     = ""
        bundle_checksum_key    = ""
        bundle_manifest_key    = ""
        bundle_commit          = ""
        bundle_sha256          = ""
        bundle_files_json      = "[]"
        images_env             = ""
        docker_compose_version = local.docker_compose_version
        docker_compose_sha256  = local.docker_compose_sha256
      })
      monitoring = false
    }
  } : {}
  tags = local.ephemeral_tags

  depends_on = [aws_route.private_nat]
}
