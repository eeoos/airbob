locals {
  app_images_env = local.services_enabled ? join("\n", [
    "APP_IMAGE=${var.app_image_reference}",
    "NODE_EXPORTER_IMAGE=${lookup(var.infra_image_references, "NODE_EXPORTER_IMAGE", "")}",
  ]) : ""

  app_runtime_revision = sha256(jsonencode({
    run_id                             = var.run_id
    app_image_reference                = var.app_image_reference
    bundle_sha256                      = var.bundle_sha256
    dataset_manifest_sha256            = var.dataset_manifest_sha256
    rds_resource_id                    = local.services_enabled ? module.rds[0].resource_id : null
    measurement_policy                 = var.measurement_policy
    accommodation_detail_cache_enabled = var.accommodation_detail_cache_enabled
  }))

  app_runtime_contract = local.services_enabled ? join("\n", [
    "AIRBOB_RUN_ID=${var.run_id}",
    "AIRBOB_RESOURCE_FENCING_TOKEN_SHA256=${sha256(tostring(var.fencing_token))}",
    "AIRBOB_MEASUREMENT_POLICY=${var.measurement_policy}",
    "AIRBOB_CACHE_ENABLED=${var.accommodation_detail_cache_enabled}",
    "AIRBOB_RDS_ENDPOINT=${module.rds[0].address}",
    "AIRBOB_RDS_MASTER_SECRET_ARN=${module.rds[0].master_secret_arn}",
    "AIRBOB_RUNTIME_REVISION=${local.app_runtime_revision}",
  ]) : ""

  app_user_data = local.services_enabled ? templatefile("${path.module}/templates/host-user-data.sh.tftpl", {
    mode                   = "service"
    service                = "app"
    region                 = var.aws_region
    account_id             = var.account_id
    bundle_bucket          = local.lab_contract.bundle_bucket_name
    bundle_archive_key     = local.bundle_archive_key
    bundle_checksum_key    = local.bundle_checksum_key
    bundle_manifest_key    = local.bundle_manifest_key
    bundle_commit          = var.bundle_commit
    bundle_sha256          = var.bundle_sha256
    bundle_files_json      = jsonencode(local.service_bundle_files)
    images_env             = local.app_images_env
    docker_compose_version = local.docker_compose_version
    docker_compose_sha256  = local.docker_compose_sha256
    runtime_contract       = local.app_runtime_contract
  }) : ""

  start_app_document = local.services_enabled ? join("\n", [
    "install -d -m 700 /opt/airbob/release/infra/aws/scripts",
    "cat > /opt/airbob/release/infra/aws/scripts/verify-app-runtime-env.sh <<'AIRBOB_APP_ENV_VALIDATOR'",
    file("${path.module}/../scripts/verify-app-runtime-env.sh"),
    "AIRBOB_APP_ENV_VALIDATOR",
    "chmod 700 /opt/airbob/release/infra/aws/scripts/verify-app-runtime-env.sh",
    file("${path.module}/templates/start-app.sh.tftpl"),
  ]) : ""
}

module "alb" {
  source = "./modules/alb"
  count  = local.services_enabled ? 1 : 0

  name_prefix       = local.bounded_name_prefix
  vpc_id            = module.network.vpc_id
  public_subnet_ids = [module.network.public_subnet_ids.primary, module.network.public_subnet_ids.secondary]
  security_group_id = module.security.security_group_ids.alb
  certificate_arn   = local.lab_contract.api_certificate_arn
  tags              = local.ephemeral_tags

  depends_on = [terraform_data.service_release_gate]
}

module "app_asg" {
  source = "./modules/app-asg"
  count  = local.services_enabled ? 1 : 0

  name_prefix                         = "airbob-${var.run_id}"
  ami_id                              = data.aws_ami.selected.id
  instance_type                       = "c6i.large"
  subnet_ids                          = local.app_subnet_ids
  security_group_ids                  = [module.security.security_group_ids.app]
  instance_profile_name               = aws_iam_instance_profile.host["app"].name
  user_data                           = local.app_user_data
  runtime_revision                    = local.app_runtime_revision
  mode                                = var.mode
  app_enabled                         = var.app_enabled
  min_size                            = local.app_capacity.min
  desired_capacity                    = local.app_capacity.desired
  max_size                            = local.app_capacity.max
  target_group_arns                   = [module.alb[0].target_group_arn]
  refresh_alarm_names                 = [module.alb[0].refresh_alarm_name]
  scaling_enabled                     = var.app_enabled && var.mode == "scaling"
  request_count_per_target_per_minute = var.request_count_per_target_per_minute
  alb_resource_label                  = module.alb[0].resource_label
  tags                                = local.ephemeral_tags

  depends_on = [
    terraform_data.data_bootstrap_gate,
    aws_iam_role_policy.app_data_plane,
  ]
}

resource "aws_ssm_document" "start_app" {
  count = local.services_enabled ? 1 : 0

  name            = "airbob-${var.run_id}-start-app"
  document_type   = "Command"
  document_format = "JSON"
  content = jsonencode({
    schemaVersion = "2.2"
    description   = "Start and verify an immutable Airbob application target"
    mainSteps = [{
      action = "aws:runShellScript"
      name   = "startAndVerifyApp"
      inputs = {
        timeoutSeconds = "1200"
        runCommand     = [local.start_app_document]
      }
    }]
  })

  tags = merge(local.ephemeral_tags, { Service = "app" })
}

resource "aws_ssm_association" "app" {
  count = local.services_enabled && var.app_enabled ? 1 : 0

  name                             = aws_ssm_document.start_app[0].name
  association_name                 = "airbob-${var.run_id}-app-${substr(local.app_runtime_revision, 0, 12)}"
  wait_for_success_timeout_seconds = 1200
  apply_only_at_cron_interval      = false
  max_concurrency                  = "100%"
  max_errors                       = "0"

  targets {
    key    = "tag:RuntimeRevision"
    values = [local.app_runtime_revision]
  }

  tags = merge(local.ephemeral_tags, { Service = "app" })

  depends_on = [
    module.app_asg,
    terraform_data.data_bootstrap_gate,
  ]
}
