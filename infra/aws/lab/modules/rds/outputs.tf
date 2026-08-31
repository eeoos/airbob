output "id" {
  value = aws_db_instance.this.id
}

output "arn" {
  value = aws_db_instance.this.arn
}

output "resource_id" {
  value = aws_db_instance.this.resource_id
}

output "address" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "master_secret_arn" {
  value = one(aws_db_instance.this.master_user_secret).secret_arn
}

output "parameter_group_name" {
  value = aws_db_parameter_group.this.name
}

output "contract" {
  value = {
    instance_class              = aws_db_instance.this.instance_class
    multi_az                    = aws_db_instance.this.multi_az
    availability_zone           = aws_db_instance.this.availability_zone
    snapshot_identifier         = var.bootstrap_mode == "snapshot" ? var.snapshot_identifier : null
    manage_master_user_password = aws_db_instance.this.manage_master_user_password
    backup_retention_period     = aws_db_instance.this.backup_retention_period
    configured_storage_gib      = var.bootstrap_mode == "dump" ? var.dump_storage_gib : null
    storage_type                = aws_db_instance.this.storage_type
    storage_encrypted           = aws_db_instance.this.storage_encrypted
  }
}
