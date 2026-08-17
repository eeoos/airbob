module "load_generator" {
  source = "./modules/load-generator"
  count  = local.services_enabled && var.load_generator_enabled ? 1 : 0

  name_prefix           = "airbob-${var.run_id}"
  ami_id                = data.aws_ami.selected.id
  subnet_id             = module.network.public_subnet_ids.primary
  security_group_id     = module.security.security_group_ids.loadgen
  instance_profile_name = aws_iam_instance_profile.host["loadgen"].name
  user_data = templatefile("${path.module}/templates/load-generator-user-data.sh.tftpl", {
    region           = var.aws_region
    runtime_revision = local.app_runtime_revision
  })
  tags = local.ephemeral_tags

  depends_on = [
    module.app_asg,
    aws_ssm_association.app,
  ]
}
