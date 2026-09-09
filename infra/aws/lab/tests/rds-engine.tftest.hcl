mock_provider "aws" {}

variables {
  name              = "airbob-lab-engine-test"
  engine_version    = "8.4.8"
  bootstrap_mode    = "dump"
  dump_storage_gib  = 20
  subnet_ids        = ["subnet-0123456789abcdef0", "subnet-0123456789abcdef1"]
  security_group_id = "sg-0123456789abcdef0"
  availability_zone = "ap-northeast-2a"
  tags              = {}
}

run "mysql_84_uses_matching_parameters_without_paid_extended_support" {
  command = plan

  module {
    source = "./modules/rds"
  }

  assert {
    condition = (
      aws_db_instance.this.engine_version == "8.4.8" &&
      aws_db_parameter_group.this.family == "mysql8.4" &&
      aws_db_instance.this.engine_lifecycle_support == "open-source-rds-extended-support-disabled" &&
      !aws_db_instance.this.auto_minor_version_upgrade &&
      !aws_db_instance.this.multi_az &&
      !aws_db_instance.this.publicly_accessible &&
      alltrue([
        for name, value in {
          binlog_format      = "ROW"
          binlog_row_image   = "FULL"
          performance_schema = "1"
          time_zone          = "UTC"
        } :
        one([for parameter in aws_db_parameter_group.this.parameter : parameter.value if parameter.name == name]) == value
      ])
    )
    error_message = "MySQL 8.4 must use its own parameter family, retain CDC/measurement settings, and disable paid Extended Support."
  }
}

run "mysql_84_snapshot_uses_same_engine_policy" {
  command = plan

  module {
    source = "./modules/rds"
  }

  variables {
    bootstrap_mode      = "snapshot"
    snapshot_identifier = "airbob-dataset-engine-test"
  }

  assert {
    condition = (
      aws_db_instance.this.snapshot_identifier == "airbob-dataset-engine-test" &&
      aws_db_parameter_group.this.family == "mysql8.4" &&
      aws_db_instance.this.engine_lifecycle_support == "open-source-rds-extended-support-disabled"
    )
    error_message = "Snapshot restores must retain the 8.4 family and Extended Support opt-out."
  }
}

run "legacy_mysql_80_keeps_existing_enrollment" {
  command = plan

  module {
    source = "./modules/rds"
  }

  variables {
    engine_version = "8.0.46"
  }

  assert {
    condition = (
      aws_db_parameter_group.this.family == "mysql8.0" &&
      aws_db_instance.this.engine_lifecycle_support == "open-source-rds-extended-support"
    )
    error_message = "Preparing 8.4 support must not silently change the legacy 8.0 enrollment policy."
  }
}

run "reject_unpinned_mysql_minor" {
  command = plan

  module {
    source = "./modules/rds"
  }

  variables {
    engine_version = "8.4"
  }

  expect_failures = [var.engine_version]
}

run "reject_unreviewed_mysql_major" {
  command = plan

  module {
    source = "./modules/rds"
  }

  variables {
    engine_version = "9.0.1"
  }

  expect_failures = [var.engine_version]
}
