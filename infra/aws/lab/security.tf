module "security" {
  source = "./modules/security"

  name_prefix          = "airbob-${var.run_id}"
  vpc_id               = module.network.vpc_id
  private_subnet_cidrs = local.private_subnet_cidrs
  dns_mode             = var.dns_mode
  alb_ingress_cidr     = var.alb_ingress_cidr
  tags                 = local.ephemeral_tags
}

resource "aws_vpc_security_group_ingress_rule" "mac_import_rds" {
  count = local.services_enabled && var.global_b_prepare_only && var.global_b_import_from_mac ? 1 : 0

  security_group_id            = module.security.security_group_ids.rds
  referenced_security_group_id = module.security.security_group_ids.nat
  ip_protocol                  = "tcp"
  from_port                    = 3306
  to_port                      = 3306
  description                  = "B import through the existing NAT instance SSM tunnel"
  tags                         = local.ephemeral_tags
}
