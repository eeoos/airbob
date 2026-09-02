locals {
  expected_contract_keys = toset([
    "schemaVersion",
    "account_id",
    "region",
    "state_bucket_name",
    "bootstrap_state_key",
    "foundation_state_key",
    "dns_state_key",
    "lab_state_key",
    "dataset_bucket_name",
    "evidence_bucket_name",
    "bundle_bucket_name",
    "lease_table_name",
    "lease_partition_key",
    "lease_expires_attribute",
    "lease_lock_id",
    "zone_id",
    "api_fqdn",
    "api_certificate_arn",
    "private_dns_zone_id",
    "private_dns_zone_name",
    "approved_rds_snapshot_identifier",
    "ecr_repositories",
  ])

  expected_ecr_repositories = {
    APP_IMAGE                    = "airbob-repo"
    REDIS_IMAGE                  = "airbob-infra/redis"
    REDIS_EXPORTER_IMAGE         = "airbob-infra/redis-exporter"
    NODE_EXPORTER_IMAGE          = "airbob-infra/node-exporter"
    KAFKA_IMAGE                  = "airbob-infra/kafka"
    DEBEZIUM_IMAGE               = "airbob-infra/debezium"
    ELASTICSEARCH_IMAGE          = "airbob-infra/elasticsearch"
    ELASTICSEARCH_EXPORTER_IMAGE = "airbob-infra/elasticsearch-exporter"
    PROMETHEUS_IMAGE             = "airbob-infra/prometheus"
    GRAFANA_IMAGE                = "airbob-infra/grafana"
  }

  lab_contract      = try(jsondecode(nonsensitive(data.aws_ssm_parameter.foundation_contract.value)), {})
  lab_contract_keys = toset(try(keys(local.lab_contract), []))
  ecr_repositories  = try(local.lab_contract.ecr_repositories, {})
  caller_identity_valid = (
    data.aws_caller_identity.current.account_id == var.account_id &&
    data.aws_region.current.region == var.aws_region
  )
  contract_schema_valid = try(
    local.lab_contract_keys == local.expected_contract_keys &&
    local.lab_contract.schemaVersion == 1 &&
    local.lab_contract.account_id == var.account_id &&
    local.lab_contract.region == var.aws_region,
    false,
  )
  state_boundaries_valid = try(
    local.lab_contract.state_bucket_name == "airbob-performance-lab-tfstate-942632789808" &&
    local.lab_contract.bootstrap_state_key == "airbob/bootstrap/terraform.tfstate" &&
    local.lab_contract.foundation_state_key == "airbob/foundation/terraform.tfstate" &&
    local.lab_contract.dns_state_key == "airbob/dns/terraform.tfstate" &&
    local.lab_contract.lab_state_key == "airbob/lab/terraform.tfstate" &&
    local.lab_contract.dataset_bucket_name == "airbob-performance-lab-dataset-942632789808" &&
    local.lab_contract.evidence_bucket_name == "airbob-performance-lab-evidence-942632789808" &&
    local.lab_contract.bundle_bucket_name == "airbob-performance-lab-bundles-942632789808",
    false,
  )
  operational_contract_valid = try(
    local.lab_contract.lease_table_name == "airbob-performance-lab-orchestration-lease" &&
    local.lab_contract.lease_partition_key == "LockName" &&
    local.lab_contract.lease_expires_attribute == "ExpiresAt" &&
    local.lab_contract.lease_lock_id == "airbob-performance-lab" &&
    can(regex("^Z[A-Z0-9]+$", local.lab_contract.zone_id)) &&
    local.lab_contract.api_fqdn == "api.airbob.cloud" &&
    can(regex("^Z[A-Z0-9]+$", local.lab_contract.private_dns_zone_id)) &&
    local.lab_contract.private_dns_zone_name == "lab.airbob.internal" &&
    (
      local.lab_contract.approved_rds_snapshot_identifier == "" ||
      (
        can(regex("^airbob-dataset-[a-z0-9][a-z0-9-]{2,47}$", local.lab_contract.approved_rds_snapshot_identifier)) &&
        !endswith(local.lab_contract.approved_rds_snapshot_identifier, "-") &&
        !strcontains(local.lab_contract.approved_rds_snapshot_identifier, "--")
      )
    ) &&
    can(regex(
      "^arn:aws:acm:ap-northeast-2:942632789808:certificate/[0-9a-f-]+$",
      local.lab_contract.api_certificate_arn,
    )),
    false,
  )
  ecr_contract_valid = try(
    toset(keys(local.ecr_repositories)) == toset(keys(local.expected_ecr_repositories)) &&
    alltrue([
      for image_key, repository_name in local.expected_ecr_repositories :
      toset(keys(local.ecr_repositories[image_key])) == toset(["url", "arn"]) &&
      local.ecr_repositories[image_key].url == "${var.account_id}.dkr.ecr.${var.aws_region}.amazonaws.com/${repository_name}" &&
      local.ecr_repositories[image_key].arn == "arn:aws:ecr:${var.aws_region}:${var.account_id}:repository/${repository_name}"
    ]),
    false,
  )

  phase2_image_keys = setsubtract(toset(keys(local.expected_ecr_repositories)), toset(["APP_IMAGE"]))
  phase2_images_valid = !contains(["services", "data-ready"], var.deployment_phase) || try(
    toset(keys(var.infra_image_references)) == local.phase2_image_keys &&
    alltrue([
      for image_key in local.phase2_image_keys :
      var.infra_image_references[image_key] == "${local.ecr_repositories[image_key].url}@${split("@", var.infra_image_references[image_key])[1]}" &&
      can(regex("@sha256:[0-9a-f]{64}$", var.infra_image_references[image_key]))
    ]),
    false,
  )
  app_image_valid = !contains(["services", "data-ready"], var.deployment_phase) || try(
    var.app_image_reference == "${local.ecr_repositories.APP_IMAGE.url}@${split("@", var.app_image_reference)[1]}" &&
    can(regex("@sha256:[0-9a-f]{64}$", var.app_image_reference)),
    false,
  )

  network_cidr = "10.42.0.0/16"
  availability_zones = {
    primary   = var.primary_availability_zone
    secondary = var.secondary_availability_zone
  }
  public_subnet_cidrs = {
    primary   = "10.42.0.0/24"
    secondary = "10.42.2.0/24"
  }
  private_subnet_cidrs = {
    primary   = "10.42.1.0/24"
    secondary = "10.42.3.0/24"
  }

  probe_enabled    = var.deployment_phase == "network"
  receipt_required = var.deployment_phase != "network"
  services_enabled = contains(["services", "data-ready"], var.deployment_phase)
  data_ready       = var.deployment_phase == "data-ready"

  bounded_name_prefix = "airbob-${substr(var.run_id, 0, 12)}-${substr(sha1(var.run_id), 0, 6)}"
  app_capacity = !var.app_enabled ? {
    min = 0, desired = 0, max = 0
    } : var.mode == "performance" ? {
    min = 1, desired = 1, max = 1
    } : {
    min = 1, desired = 1, max = 4
  }
  app_availability_zones = var.mode == "performance" ? [
    var.primary_availability_zone,
    ] : [
    var.primary_availability_zone,
    var.secondary_availability_zone,
  ]
  app_subnet_ids = var.mode == "performance" ? [
    module.network.private_subnet_ids.primary,
    ] : [
    module.network.private_subnet_ids.primary,
    module.network.private_subnet_ids.secondary,
  ]

  dataset_prefix       = "datasets/${var.dataset_release}"
  dataset_manifest_key = "${local.dataset_prefix}/manifest.json"
  dataset_manifest = local.services_enabled ? try(
    jsondecode(nonsensitive(data.aws_s3_object.dataset_manifest[0].body)),
    null,
  ) : null
  dataset_release_kind        = try(local.dataset_manifest.releaseKind, null)
  dataset_search_enabled      = try(local.dataset_manifest.search.enabled, false)
  dataset_expected_table_rows = try(local.dataset_manifest.mysql.expectedTableRows, {})

  bundle_archive_name  = "airbob-service-bundles-${var.bundle_commit}.tar.gz"
  bundle_prefix        = "service-bundles/${var.bundle_commit}"
  bundle_archive_key   = "${local.bundle_prefix}/${local.bundle_archive_name}"
  bundle_checksum_key  = "${local.bundle_archive_key}.sha256"
  bundle_manifest_key  = "${local.bundle_prefix}/airbob-service-bundles-${var.bundle_commit}.manifest.json"
  service_bundle_files = jsondecode(file("${path.module}/../bundles/manifest.json")).files

  docker_compose_version = "2.40.2"
  docker_compose_sha256  = "6c964d9655cd629ef43c5dc75d9612c2da319237debee54a7aef217e9f362b88"

  ephemeral_tags = {
    Project      = "airbob"
    Environment  = "performance-lab"
    Stack        = "lab"
    ManagedBy    = "terraform"
    Persistence  = "ephemeral"
    ExpiresAt    = var.expires_at
    RunId        = var.run_id
    FencingToken = tostring(var.fencing_token)
  }

  service_hosts = {
    redis = {
      instance_type = "t3.small"
      volume_size   = 20
    }
    kafka = {
      instance_type = "t3.medium"
      volume_size   = 30
    }
    debezium = {
      instance_type = "t3.medium"
      volume_size   = 20
    }
    elasticsearch = {
      instance_type = "t3.medium"
      volume_size   = 40
    }
    monitoring = {
      instance_type = "t3.small"
      volume_size   = 30
    }
  }
}
