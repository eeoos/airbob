locals {
  zone_name = "airbob.cloud"
  api_fqdn  = "api.airbob.cloud"

  foundation_dns_record_names = distinct(concat(
    [for record in values(var.static_dns_records) : lower(record.name)],
    [lower(trimsuffix(local.api_certificate_validation.resource_record_name, "."))],
  ))
  foundation_dns_record_types = distinct(concat(
    [for record in values(var.static_dns_records) : upper(record.type)],
    [upper(local.api_certificate_validation.resource_record_type)],
  ))

  required_tags = {
    Project     = "airbob"
    Environment = "performance-lab"
    Stack       = "foundation"
    ManagedBy   = "terraform"
    Persistence = "persistent"
  }

  state_keys = {
    bootstrap  = "airbob/bootstrap/terraform.tfstate"
    foundation = "airbob/foundation/terraform.tfstate"
    dns        = "airbob/dns/terraform.tfstate"
    lab        = "airbob/lab/terraform.tfstate"
  }

  state_bucket_posture_read_actions = [
    "s3:GetBucketLocation",
    "s3:GetBucketOwnershipControls",
    "s3:GetBucketPolicy",
    "s3:GetBucketPublicAccessBlock",
    "s3:GetBucketTagging",
    "s3:GetBucketVersioning",
    "s3:GetEncryptionConfiguration",
  ]

  lab_operator_state_keys = [local.state_keys.dns, local.state_keys.lab]

  github_subjects = {
    foundation = var.github_foundation_subject
    lab        = var.github_lab_subject
    image      = var.github_image_subject
  }

  github_subjects_are_legacy = alltrue([
    for subject in values(local.github_subjects) : startswith(subject, "repo:eeoos/airbob:")
  ])
  github_subjects_are_immutable = alltrue([
    for subject in values(local.github_subjects) : startswith(subject, "repo:eeoos@119295425/airbob@1056501820:")
  ])

  infra_ecr_repositories = {
    redis                  = "airbob-infra/redis"
    redis_exporter         = "airbob-infra/redis-exporter"
    node_exporter          = "airbob-infra/node-exporter"
    kafka                  = "airbob-infra/kafka"
    debezium               = "airbob-infra/debezium"
    elasticsearch          = "airbob-infra/elasticsearch"
    elasticsearch_exporter = "airbob-infra/elasticsearch-exporter"
    prometheus             = "airbob-infra/prometheus"
    grafana                = "airbob-infra/grafana"
  }

  infra_ecr_image_variables = {
    redis                  = "REDIS_IMAGE"
    redis_exporter         = "REDIS_EXPORTER_IMAGE"
    node_exporter          = "NODE_EXPORTER_IMAGE"
    kafka                  = "KAFKA_IMAGE"
    debezium               = "DEBEZIUM_IMAGE"
    elasticsearch          = "ELASTICSEARCH_IMAGE"
    elasticsearch_exporter = "ELASTICSEARCH_EXPORTER_IMAGE"
    prometheus             = "PROMETHEUS_IMAGE"
    grafana                = "GRAFANA_IMAGE"
  }

  all_ecr_repository_arns = concat(
    [aws_ecr_repository.application.arn],
    [for repository in aws_ecr_repository.infrastructure : repository.arn],
  )

  ecr_repositories = merge(
    {
      APP_IMAGE = {
        url = aws_ecr_repository.application.repository_url
        arn = aws_ecr_repository.application.arn
      }
    },
    {
      for repository_key, image_key in local.infra_ecr_image_variables : image_key => {
        url = aws_ecr_repository.infrastructure[repository_key].repository_url
        arn = aws_ecr_repository.infrastructure[repository_key].arn
      }
    },
  )

  ecr_repository_urls = {
    for image_key, repository in local.ecr_repositories : image_key => repository.url
  }

  ecr_repository_arns = {
    for image_key, repository in local.ecr_repositories : image_key => repository.arn
  }

  lease_partition_key     = "LockName"
  lease_expires_attribute = "ExpiresAt"
  lease_lock_id           = "airbob-performance-lab"

  github_trust_common = {
    "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
  }
}
