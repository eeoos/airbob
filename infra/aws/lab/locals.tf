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
}
