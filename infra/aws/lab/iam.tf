locals {
  service_role_names = toset(keys(local.service_hosts))
  active_host_roles = setunion(
    # Keep the no-cost probe identity until teardown. Removing one member from
    # this shared for_each collection during the probe-cleared transition can
    # cycle with the NAT instance's stored create-before-destroy dependencies.
    toset(["nat", "probe"]),
    local.services_enabled ? local.service_role_names : toset([]),
    local.application_infrastructure_enabled ? toset(["app"]) : toset([]),
    local.services_enabled && var.load_generator_enabled ? toset(["loadgen"]) : toset([]),
  )
  data_plane_host_roles = local.services_enabled ? local.service_role_names : toset([])
  measurement_host_roles = local.services_enabled && var.load_generator_enabled ? toset([
    "debezium",
    "loadgen",
    "monitoring",
  ]) : toset([])
  phase2_ecr_arns = [
    for image_key in local.phase2_image_keys : local.ecr_repositories[image_key].arn
  ]
  ec2_assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "Ec2AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "app_data_plane" {
  count = local.application_infrastructure_enabled ? 1 : 0

  name = "airbob-performance-lab-app-data-plane"
  role = aws_iam_role.host["app"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadBundleRelease"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = "arn:aws:s3:::${local.lab_contract.bundle_bucket_name}/${local.bundle_prefix}/*"
      },
      {
        Sid    = "PullApplicationImages"
        Effect = "Allow"
        Action = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages"]
        Resource = [
          local.ecr_repositories.APP_IMAGE.arn,
          local.ecr_repositories.NODE_EXPORTER_IMAGE.arn,
        ]
      },
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid      = "ReadRdsMasterSecret"
        Effect   = "Allow"
        Action   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue"]
        Resource = module.rds[0].master_secret_arn
      },
    ]
  })
}

resource "aws_iam_role" "host" {
  for_each = local.active_host_roles

  name                 = "airbob-lab-host-${var.run_id}-${each.key}"
  assume_role_policy   = local.ec2_assume_role_policy
  permissions_boundary = "arn:aws:iam::${var.account_id}:policy/airbob-performance-lab-host-boundary"

  tags = merge(local.ephemeral_tags, { Service = each.key })
}

resource "aws_iam_instance_profile" "host" {
  for_each = aws_iam_role.host

  name = each.value.name
  role = each.value.name

  tags = merge(local.ephemeral_tags, { Service = each.key })
}

resource "aws_iam_role_policy_attachment" "ssm_core" {
  for_each = aws_iam_role.host

  role       = each.value.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "data_plane" {
  for_each = local.data_plane_host_roles

  name = "airbob-performance-lab-${each.key}-data-plane"
  role = aws_iam_role.host[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadBundleRelease"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = "arn:aws:s3:::${local.lab_contract.bundle_bucket_name}/${local.bundle_prefix}/*"
      },
      {
        Sid      = "PullPhase2Images"
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages"]
        Resource = local.phase2_ecr_arns
      },
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid      = "WritePhase2Evidence"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:PutObjectTagging"]
        Resource = "arn:aws:s3:::${local.lab_contract.evidence_bucket_name}/phase2/${var.run_id}/*"
        Condition = {
          StringEquals = {
            "s3:RequestObjectTag/Retention" = "summary"
          }
        }
      },
    ]
  })
}

resource "aws_iam_role_policy" "measurement_data_plane" {
  for_each = local.measurement_host_roles

  name = "airbob-performance-lab-${each.key}-measurement"
  role = aws_iam_role.host[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [
        {
          Sid      = "WriteMeasurementEvidence"
          Effect   = "Allow"
          Action   = ["s3:PutObject", "s3:PutObjectTagging"]
          Resource = "arn:aws:s3:::${local.lab_contract.evidence_bucket_name}/measurements/${var.run_id}/*"
          Condition = {
            StringEquals = {
              "s3:RequestObjectTag/Retention" = ["raw", "summary"]
            }
          }
        },
      ],
      each.key == "monitoring" ? [] : [
        {
          Sid      = "ReadMeasurementInputs"
          Effect   = "Allow"
          Action   = ["s3:GetObject", "s3:GetObjectVersion"]
          Resource = ["arn:aws:s3:::${local.lab_contract.evidence_bucket_name}/measurement-inputs/${var.run_id}/*"]
        },
      ],
      [
        for statement in [
          {
            Sid    = "ReadSelectedBenchmarkManifest"
            Effect = "Allow"
            Action = ["s3:GetObject", "s3:GetObjectVersion"]
            Resource = [
              "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.dataset_prefix}/benchmark/manifest.json",
              "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.dataset_prefix}/benchmark/dataset-manifest.json",
            ]
          },
        ] : statement if each.key == "loadgen"
      ],
    )
  })
}

resource "aws_iam_role_policy" "probe_egress" {
  count = local.probe_enabled ? 1 : 0

  name = "airbob-performance-lab-probe-egress"
  role = aws_iam_role.host["probe"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "VerifyEvidenceBucketPath"
      Effect   = "Allow"
      Action   = "s3:GetBucketLocation"
      Resource = "arn:aws:s3:::${local.lab_contract.evidence_bucket_name}"
    }]
  })
}

resource "aws_iam_role_policy" "monitoring_discovery" {
  count = local.dependency_services_enabled ? 1 : 0

  name = "airbob-performance-lab-monitoring-discovery"
  role = aws_iam_role.host["monitoring"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "DiscoverLabMetrics"
      Effect = "Allow"
      Action = [
        "cloudwatch:GetMetricData",
        "cloudwatch:GetMetricStatistics",
        "cloudwatch:ListMetrics",
        "ec2:DescribeInstances",
        "ec2:DescribeTags",
      ]
      Resource = "*"
    }]
  })
}

resource "aws_iam_role_policy" "data_bootstrap" {
  count = local.services_enabled ? 1 : 0

  name = "airbob-performance-lab-data-bootstrap"
  role = aws_iam_role.host["debezium"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [for statement in [
      {
        Sid      = "ReadBootstrapOrchestrationLease"
        Effect   = "Allow"
        Action   = "dynamodb:GetItem"
        Resource = "arn:aws:dynamodb:${var.aws_region}:${var.account_id}:table/${local.lab_contract.lease_table_name}"
        Condition = {
          "ForAllValues:StringEquals" = {
            "dynamodb:LeadingKeys" = [local.lab_contract.lease_lock_id]
          }
          Null = {
            "dynamodb:LeadingKeys" = "false"
          }
        }
      },
      {
        # DescribeDBInstances supports a DB ARN; the runner names this instance.
        Sid      = "DescribeBootstrapRds"
        Effect   = "Allow"
        Action   = "rds:DescribeDBInstances"
        Resource = module.rds[0].arn
      },
      {
        # These two read APIs do not support resource-level authorization.
        Sid      = "ReadBootstrapCapacityAndWriters"
        Effect   = "Allow"
        Action   = ["autoscaling:DescribeAutoScalingGroups", "cloudwatch:GetMetricStatistics"]
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:RequestedRegion" = var.aws_region
          }
        }
      },
      {
        Sid      = "ReadSelectedDatasetRelease"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.dataset_prefix}/*"
      },
      {
        Sid      = "ReadRdsMasterSecret"
        Effect   = "Allow"
        Action   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue"]
        Resource = module.rds[0].master_secret_arn
      },
      {
        Sid      = "ManageEphemeralDebeziumCredentialValue"
        Effect   = "Allow"
        Action   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
        Resource = aws_secretsmanager_secret.debezium[0].arn
      },
      {
        Sid      = "WriteDataBootstrapReceipt"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:PutObjectTagging"]
        Resource = "arn:aws:s3:::${local.lab_contract.evidence_bucket_name}/data-bootstrap/${var.run_id}/*"
        Condition = {
          StringEquals = {
            "s3:RequestObjectTag/Retention" = "summary"
          }
        }
      },
      {
        Sid      = "ReadDataBootstrapReceipt"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = "arn:aws:s3:::${local.lab_contract.evidence_bucket_name}/data-bootstrap/${var.run_id}/*"
      },
    ] : statement if statement.Sid != "ReadSelectedDatasetRelease" || (!var.global_b_prepare_only && !var.global_b_services && !var.global_b_snapshot_restore_only)]
  })
}

resource "aws_iam_role_policy" "elasticsearch_snapshot" {
  count = local.legacy_services_enabled ? 1 : 0

  name = "airbob-performance-lab-elasticsearch-snapshot"
  role = aws_iam_role.host["elasticsearch"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "DatasetSnapshotBucketLocation"
        Effect   = "Allow"
        Action   = "s3:GetBucketLocation"
        Resource = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}"
      },
      {
        Sid      = "ListDatasetSnapshotRepository"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}"
        Condition = {
          StringLike = {
            "s3:prefix" = ["elasticsearch/releases/${var.dataset_release}/*"]
          }
        }
      },
      {
        Sid      = "ReadDatasetSnapshotRepository"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/elasticsearch/releases/${var.dataset_release}/*"
      },
    ]
  })
}

locals {
  growth_b_preparation_refs = var.global_b_prepare_only && local.services_enabled ? concat(
    [{ key = local.dataset_manifest_key, versionId = var.global_b_manifest_version_id }],
    try(values(local.dataset_manifest.files), []), try(values(local.growth_b_envelope.objects), []),
  ) : []
  growth_b_service_read_refs = var.global_b_services && local.services_enabled ? concat(
    [{ bucket = local.lab_contract.dataset_bucket_name, ref = { key = local.dataset_manifest_key, versionId = var.global_b_manifest_version_id } }],
    values(local.growth_b_service_refs),
    try(local.dataset_manifest.search.restoreReceipt, null) == null ? [] : [{ bucket = local.lab_contract.evidence_bucket_name, ref = local.dataset_manifest.search.restoreReceipt }],
  ) : []
  growth_b_app_read_refs = var.global_b_services && local.services_enabled ? concat(
    try([{ bucket = local.lab_contract.dataset_bucket_name, ref = local.dataset_manifest.preparation.rdsCaBundle }], []),
    try([{ bucket = local.lab_contract.dataset_bucket_name, ref = local.dataset_manifest.appRuntimeBinding }], []),
    var.global_b_readiness_receipt == null ? [] : [{ bucket = local.lab_contract.evidence_bucket_name, ref = {
      key = var.global_b_readiness_receipt.key, versionId = var.global_b_readiness_receipt.version_id
    } }],
  ) : []
  growth_b_transport_refs = var.global_b_services && local.services_enabled ? try(concat(
    [{ key = local.dataset_manifest.search.transport.key, versionId = local.dataset_manifest.search.transport.versionId }],
    [for item in values(local.growth_b_service_transport.objects) : item if !startswith(item.key, "${local.growth_b_search_prefix}/native/")],
    [local.growth_b_service_transport.sql.consumerManifest, local.growth_b_service_transport.sql.checksums, local.growth_b_service_transport.sql.dump],
  ), []) : []
}

resource "aws_iam_role_policy" "growth_b_preparation_inputs" {
  count = local.services_enabled && var.global_b_prepare_only ? 1 : 0
  name  = "airbob-performance-lab-b-preparation-inputs"
  role  = aws_iam_role.host["debezium"].id
  # Selected release prefixes and a finite version set keep the role below IAM's aggregate inline
  # policy limit. Every downloaded pair is additionally verified by the sealed
  # wrapper and the host's exact VersionId/size/SHA gate.
  policy = jsonencode({ Version = "2012-10-17", Statement = [{
    Sid = "ReadPinnedBPreparation", Effect = "Allow", Action = "s3:GetObjectVersion"
    Resource = [
      "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.dataset_prefix}/*",
      "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/datasets/${var.dataset_release}-aws-preparation/files/*",
      "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.dataset_manifest_key}",
    ]
    Condition = { StringEquals = { "s3:VersionId" = [for ref in local.growth_b_preparation_refs : ref.versionId] } }
  }] })
}

resource "aws_iam_role_policy" "growth_b_service_inputs" {
  count = local.services_enabled && var.global_b_services ? 1 : 0
  name  = "airbob-performance-lab-b-service-inputs"
  role  = aws_iam_role.host["debezium"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [for n, item in local.growth_b_service_read_refs : {
    Sid       = "ReadPinnedBService${n}", Effect = "Allow", Action = "s3:GetObjectVersion"
    Resource  = "arn:aws:s3:::${item.bucket}/${item.ref.key}"
    Condition = { StringEquals = { "s3:VersionId" = item.ref.versionId } }
  }] })
}

resource "aws_iam_role_policy" "growth_b_app_inputs" {
  count = local.services_enabled && var.global_b_services ? 1 : 0
  name  = "airbob-performance-lab-b-app-inputs"
  role  = aws_iam_role.host["app"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = concat([{
    Sid = "DescribeSelectedBApplicationRds", Effect = "Allow", Action = "rds:DescribeDBInstances", Resource = module.rds[0].arn
    }], [for n, item in local.growth_b_app_read_refs : {
    Sid       = "ReadPinnedBApp${n}", Effect = "Allow", Action = "s3:GetObjectVersion"
    Resource  = "arn:aws:s3:::${item.bucket}/${item.ref.key}"
    Condition = { StringEquals = { "s3:VersionId" = item.ref.versionId } }
  }]) })
}

resource "aws_iam_role_policy" "growth_b_search_inputs" {
  count = local.services_enabled && var.global_b_services ? 1 : 0
  name  = "airbob-performance-lab-b-search-inputs"
  role  = aws_iam_role.host["elasticsearch"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = concat([
    {
      Sid = "ReadBSourceVerificationInputs", Effect = "Allow", Action = "s3:GetObjectVersion"
      Resource = [
        "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.dataset_prefix}/*",
        "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/datasets/${var.dataset_release}-aws-preparation/*",
        "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.growth_b_service_prefix}/files/*",
      ]
    },
    {
      Sid      = "ReadBTransportCompletionHead", Effect = "Allow", Action = "s3:GetObject"
      Resource = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.growth_b_search_prefix}/transport-manifest.json"
    },
    {
      Sid      = "BSearchBucketLocation", Effect = "Allow", Action = "s3:GetBucketLocation"
      Resource = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}"
    },
    {
      Sid       = "ListExactBSearchRelease", Effect = "Allow", Action = ["s3:ListBucket", "s3:ListBucketVersions"]
      Resource  = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}"
      Condition = { StringLike = { "s3:prefix" = ["${local.growth_b_search_prefix}/*"] } }
    },
    {
      # Elasticsearch's readonly repository API reads current native keys. The
      # transport verifier separately proves those current VersionIds and bytes.
      Sid      = "ReadExactNativeBRepository", Effect = "Allow", Action = ["s3:GetObject", "s3:GetObjectVersion"]
      Resource = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${local.growth_b_search_prefix}/native/*"
    },
    ], [for n, ref in local.growth_b_transport_refs : {
      Sid       = "ReadPinnedBSearch${n}", Effect = "Allow", Action = "s3:GetObjectVersion"
      Resource  = "arn:aws:s3:::${local.lab_contract.dataset_bucket_name}/${ref.key}"
      Condition = { StringEquals = { "s3:VersionId" = ref.versionId } }
  }]) })
}

# RDS depends on the initial core service association, so its credential read
# policy is attached separately after RDS exists; the native S3 policy has no
# RDS dependency and can be ready before Elasticsearch starts.
resource "aws_iam_role_policy" "growth_b_search_source_inputs" {
  count = local.services_enabled && var.global_b_services ? 1 : 0
  name  = "airbob-performance-lab-b-search-source"
  role  = aws_iam_role.host["elasticsearch"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    {
      Sid      = "ReadExactBSourceCredential", Effect = "Allow", Action = "secretsmanager:GetSecretValue"
      Resource = module.rds[0].master_secret_arn
    },
    {
      Sid      = "DescribeExactBSearchSourceRds", Effect = "Allow", Action = "rds:DescribeDBInstances"
      Resource = module.rds[0].arn
    },
  ] })
}

resource "aws_iam_role_policy" "growth_b_snapshot_read" {
  count = local.services_enabled && (var.global_b_services || var.global_b_snapshot_restore_only) ? 1 : 0
  name  = "airbob-performance-lab-b-snapshot-read"
  role  = aws_iam_role.host["debezium"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Sid = "ReadRegionalBSourceSnapshotInventory", Effect = "Allow", Action = ["rds:DescribeDBInstances", "rds:DescribeDBSnapshots"], Resource = "*",
    Condition = { StringEquals = { "aws:RequestedRegion" = var.aws_region } } },
    { Sid      = "ReadBSourceSnapshotMetadata", Effect = "Allow", Action = ["rds:DescribeDBSnapshotAttributes", "rds:ListTagsForResource"],
      Resource = ["arn:aws:rds:${var.aws_region}:${var.account_id}:snapshot:airbob-dataset-b-*", module.rds[0].arn],
    Condition = { StringEquals = { "aws:RequestedRegion" = var.aws_region } } },
  ] })
}

resource "aws_iam_role_policy" "growth_b_snapshot_restore_inputs" {
  count = local.services_enabled && var.global_b_snapshot_restore_only ? 1 : 0
  name  = "airbob-performance-lab-b-snapshot-restore-inputs"
  role  = aws_iam_role.host["debezium"].id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    # The controller installs the remaining exact-version inputs only for the
    # selected operation deadline, then removes that temporary read policy.
    { Sid      = "ReadExactBSnapshotProvenance", Effect = "Allow", Action = "s3:GetObjectVersion",
      Resource = "arn:aws:s3:::${local.growth_b_snapshot_bucket}/${var.global_b_snapshot_provenance.key}",
    Condition = { StringEquals = { "s3:VersionId" = var.global_b_snapshot_provenance.version_id } } },
  ] })
}
