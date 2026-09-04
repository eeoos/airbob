locals {
  start_service_document = templatefile("${path.module}/templates/start-service.sh.tftpl", {
    account_id      = var.account_id
    region          = var.aws_region
    run_id          = var.run_id
    evidence_bucket = local.lab_contract.evidence_bucket_name
    bundle_sha256   = var.bundle_sha256
  })
  bootstrap_data_command = local.services_enabled ? join("\n", [
    "set -euo pipefail",
    "umask 077",
    "install -d -m 700 /opt/airbob/bootstrap-helpers",
    "cat > /opt/airbob/bootstrap-helpers/coupon_prepare.lua <<'AIRBOB_COUPON_LUA'",
    file("${path.module}/../../../src/main/resources/lua/coupon_prepare.lua"),
    "AIRBOB_COUPON_LUA",
    "cat > /opt/airbob/bootstrap-helpers/bootstrap-data.sh.gz.b64 <<'AIRBOB_BOOTSTRAP_DATA'",
    base64gzip(file("${path.module}/../scripts/bootstrap-data.sh")),
    "AIRBOB_BOOTSTRAP_DATA",
    "base64 --decode /opt/airbob/bootstrap-helpers/bootstrap-data.sh.gz.b64 | gzip --decompress > /opt/airbob/bootstrap-helpers/bootstrap-data.sh",
    "printf '%s  %s\\n' '${filesha256("${path.module}/../scripts/bootstrap-data.sh")}' /opt/airbob/bootstrap-helpers/bootstrap-data.sh | sha256sum --check --status",
    "chmod 700 /opt/airbob/bootstrap-helpers/bootstrap-data.sh",
    "chmod 600 /opt/airbob/bootstrap-helpers/coupon_prepare.lua",
    "export AIRBOB_REGION='${var.aws_region}'",
    "export AIRBOB_RUN_ID='${var.run_id}'",
    "export AIRBOB_DATASET_BUCKET='${local.lab_contract.dataset_bucket_name}'",
    "export AIRBOB_EVIDENCE_BUCKET='${local.lab_contract.evidence_bucket_name}'",
    "export AIRBOB_DATASET_RELEASE='${var.dataset_release}'",
    "export AIRBOB_DATASET_MANIFEST_SHA256='${var.dataset_manifest_sha256}'",
    "export AIRBOB_DATABASE_BOOTSTRAP='${var.database_bootstrap}'",
    "export AIRBOB_RDS_ENDPOINT='${module.rds[0].address}'",
    "export AIRBOB_RDS_RESOURCE_ID='${module.rds[0].resource_id}'",
    "export AIRBOB_RDS_ENGINE_VERSION='${var.rds_engine_version}'",
    "export AIRBOB_RDS_MASTER_SECRET_ARN='${module.rds[0].master_secret_arn}'",
    "export AIRBOB_DEBEZIUM_SECRET_ARN='${aws_secretsmanager_secret.debezium[0].arn}'",
    "export AIRBOB_ELASTICSEARCH_IMAGE_DIGEST='${split("@", var.infra_image_references["ELASTICSEARCH_IMAGE"])[1]}'",
    "export AIRBOB_COUPON_LUA_FILE=/opt/airbob/bootstrap-helpers/coupon_prepare.lua",
    "/opt/airbob/bootstrap-helpers/bootstrap-data.sh",
  ]) : ""
}

resource "aws_ssm_document" "start_service" {
  count = local.services_enabled ? 1 : 0

  name            = "airbob-${var.run_id}-start-service"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Start and verify one immutable Airbob Phase 2 service bundle"
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "startAndVerify"
      inputs = {
        timeoutSeconds = "2400"
        runCommand     = [local.start_service_document]
      }
    }]
  })

  tags = merge(local.ephemeral_tags, { Service = "service-bootstrap" })
}

resource "aws_ssm_association" "core_services" {
  for_each = local.services_enabled ? toset(["redis", "kafka", "elasticsearch"]) : toset([])

  name                             = aws_ssm_document.start_service[0].name
  association_name                 = "airbob-${var.run_id}-${each.key}"
  wait_for_success_timeout_seconds = 2700
  apply_only_at_cron_interval      = false

  targets {
    key    = "InstanceIds"
    values = [module.service_hosts.instance_ids[each.key]]
  }

  tags = merge(local.ephemeral_tags, { Service = each.key })

  depends_on = [aws_route53_record.private_service]
}

resource "aws_ssm_association" "debezium" {
  count = local.services_enabled ? 1 : 0

  name                             = aws_ssm_document.start_service[0].name
  association_name                 = "airbob-${var.run_id}-debezium"
  wait_for_success_timeout_seconds = 2700

  targets {
    key    = "InstanceIds"
    values = [module.service_hosts.instance_ids.debezium]
  }

  tags = merge(local.ephemeral_tags, { Service = "debezium" })

  depends_on = [aws_ssm_association.core_services]
}

resource "aws_ssm_association" "monitoring" {
  count = local.services_enabled ? 1 : 0

  name                             = aws_ssm_document.start_service[0].name
  association_name                 = "airbob-${var.run_id}-monitoring"
  wait_for_success_timeout_seconds = 2700

  targets {
    key    = "InstanceIds"
    values = [module.service_hosts.instance_ids.monitoring]
  }

  tags = merge(local.ephemeral_tags, { Service = "monitoring" })

  depends_on = [
    aws_ssm_association.core_services,
    aws_ssm_association.debezium,
  ]
}

resource "aws_ssm_document" "bootstrap_data" {
  count = local.services_enabled ? 1 : 0

  name            = "airbob-${var.run_id}-bootstrap-data"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Verify and bootstrap the immutable Airbob Phase 3 dataset"
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "bootstrapData"
      inputs = {
        # The operator owns the earlier absolute run deadline. This fallback
        # must not allocate a shorter, speculative data-bootstrap window.
        timeoutSeconds = "18000"
        runCommand     = [local.bootstrap_data_command]
      }
    }]
  })

  tags = merge(local.ephemeral_tags, { Service = "data-bootstrap" })
}

resource "aws_ssm_association" "data_bootstrap" {
  count = local.services_enabled ? 1 : 0

  name                             = aws_ssm_document.bootstrap_data[0].name
  association_name                 = "airbob-${var.run_id}-data-bootstrap"
  wait_for_success_timeout_seconds = 18300

  targets {
    key    = "InstanceIds"
    values = [module.service_hosts.instance_ids.debezium]
  }

  tags = merge(local.ephemeral_tags, { Service = "data-bootstrap" })

  depends_on = [
    module.rds,
    aws_secretsmanager_secret.debezium,
    aws_iam_role_policy.data_bootstrap,
    aws_iam_role_policy.elasticsearch_snapshot,
    aws_ssm_association.core_services,
    aws_ssm_association.debezium,
  ]
}
