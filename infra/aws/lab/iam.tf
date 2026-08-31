locals {
  service_role_names = toset(keys(local.service_hosts))
  active_host_roles = setunion(
    toset(["nat"]),
    local.probe_enabled ? toset(["probe"]) : toset([]),
    local.services_enabled ? local.service_role_names : toset([]),
    local.services_enabled ? toset(["app"]) : toset([]),
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
  count = local.services_enabled ? 1 : 0

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
  count = local.services_enabled ? 1 : 0

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
    Statement = [
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
    ]
  })
}

resource "aws_iam_role_policy" "elasticsearch_snapshot" {
  count = local.services_enabled ? 1 : 0

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
