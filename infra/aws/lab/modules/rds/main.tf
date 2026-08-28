resource "aws_db_subnet_group" "this" {
  name       = var.name
  subnet_ids = var.subnet_ids
  tags       = var.tags
}

resource "aws_db_parameter_group" "this" {
  name   = var.name
  family = "mysql8.0"
  tags   = var.tags

  parameter {
    name         = "binlog_format"
    value        = "ROW"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "binlog_row_image"
    value        = "FULL"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "performance_schema"
    value        = "1"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "time_zone"
    value        = "UTC"
    apply_method = "pending-reboot"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "this" {
  identifier = var.name

  engine         = "mysql"
  engine_version = var.engine_version
  instance_class = "db.t3.micro"

  allocated_storage           = var.bootstrap_mode == "dump" ? var.dump_storage_gib : null
  storage_type                = "gp3"
  storage_encrypted           = true
  snapshot_identifier         = var.bootstrap_mode == "snapshot" ? var.snapshot_identifier : null
  db_name                     = var.bootstrap_mode == "dump" ? "airbobdb" : null
  username                    = var.bootstrap_mode == "dump" ? "airbob_admin" : null
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.this.name
  parameter_group_name   = aws_db_parameter_group.this.name
  vpc_security_group_ids = [var.security_group_id]
  availability_zone      = var.availability_zone
  port                   = 3306

  multi_az                     = false
  publicly_accessible          = false
  backup_retention_period      = 1
  delete_automated_backups     = true
  skip_final_snapshot          = true
  deletion_protection          = false
  apply_immediately            = true
  auto_minor_version_upgrade   = false
  copy_tags_to_snapshot        = true
  monitoring_interval          = 0
  performance_insights_enabled = false

  tags = var.tags

  lifecycle {
    precondition {
      condition = (
        (var.bootstrap_mode == "dump" && var.snapshot_identifier == null) ||
        (var.bootstrap_mode == "snapshot" && var.snapshot_identifier != null)
      )
      error_message = "Snapshot mode requires one prevalidated snapshot and dump mode forbids one."
    }
  }
}
