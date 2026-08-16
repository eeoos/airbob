locals {
  start_service_document = templatefile("${path.module}/templates/start-service.sh.tftpl", {
    account_id      = var.account_id
    region          = var.aws_region
    run_id          = var.run_id
    evidence_bucket = local.lab_contract.evidence_bucket_name
    bundle_sha256   = var.bundle_sha256
  })
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
        timeoutSeconds = "1200"
        runCommand     = [local.start_service_document]
      }
    }]
  })

  tags = local.ephemeral_tags
}

resource "aws_ssm_association" "core_services" {
  for_each = local.services_enabled ? toset(["redis", "kafka", "elasticsearch"]) : toset([])

  name                             = aws_ssm_document.start_service[0].name
  association_name                 = "airbob-${var.run_id}-${each.key}"
  wait_for_success_timeout_seconds = 1200
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
  wait_for_success_timeout_seconds = 1200

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
  wait_for_success_timeout_seconds = 1200

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
