module "rds" {
  source = "./modules/rds"
  count  = local.services_enabled ? 1 : 0

  name                = "airbob-${var.run_id}"
  engine_version      = var.rds_engine_version
  bootstrap_mode      = var.database_bootstrap
  snapshot_identifier = var.database_bootstrap == "snapshot" ? var.rds_snapshot_identifier : null
  subnet_ids = [
    module.network.private_subnet_ids.primary,
    module.network.private_subnet_ids.secondary,
  ]
  security_group_id = module.security.security_group_ids.rds
  availability_zone = var.primary_availability_zone
  tags              = merge(local.ephemeral_tags, { Service = "rds" })

  depends_on = [
    terraform_data.network_receipt_gate,
    terraform_data.probe_clearance_gate,
    terraform_data.dataset_release_gate,
    aws_route.private_nat,
  ]
}

resource "aws_secretsmanager_secret" "debezium" {
  count = local.services_enabled ? 1 : 0

  name                    = "airbob/${var.run_id}/debezium"
  description             = "Ephemeral Debezium credential container; its value is generated only on the bootstrap host."
  recovery_window_in_days = 0

  tags = merge(local.ephemeral_tags, { Service = "debezium" })
}
