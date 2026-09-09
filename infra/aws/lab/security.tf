module "security" {
  source = "./modules/security"

  name_prefix          = "airbob-${var.run_id}"
  vpc_id               = module.network.vpc_id
  private_subnet_cidrs = local.private_subnet_cidrs
  dns_mode             = var.dns_mode
  alb_ingress_cidr     = var.alb_ingress_cidr
  tags                 = local.ephemeral_tags
}
