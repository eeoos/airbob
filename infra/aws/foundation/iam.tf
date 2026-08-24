locals {
  approved_local_principals_statements = {
    for role, principals in {
      foundation = var.foundation_local_principal_arns
      lab        = var.lab_local_principal_arns
      dataset    = var.dataset_publisher_local_principal_arns
      } : role => merge(
      {
        Sid       = "ApprovedLocalPrincipals"
        Effect    = "Allow"
        Principal = { AWS = sort(tolist(principals)) }
        Action    = "sts:AssumeRole"
      },
      (role == "dataset" || var.local_principal_requires_mfa) ? {
        Condition = {
          Bool = {
            "aws:MultiFactorAuthPresent" = "true"
          }
        }
      } : {},
    )
  }

  lab_operator_managed_policies = {
    foundation_base = {
      name     = "airbob-lab-operator-foundation-base"
      document = local.lab_operator_policy
    }
    compute_ec2_iam = {
      name     = "airbob-lab-operator-compute-ec2-iam"
      document = local.lab_compute_ec2_iam_policy
    }
    compute_ssm_dns = {
      name     = "airbob-lab-operator-compute-ssm-dns"
      document = local.lab_compute_ssm_dns_policy
    }
    data_compute = {
      name     = "airbob-lab-operator-data-compute"
      document = local.lab_data_compute_policy
    }
    app_compute = {
      name     = "airbob-lab-operator-app-compute"
      document = local.lab_app_compute_policy
    }
  }

  github_role_trust = {
    foundation = merge(local.github_trust_common, {
      "token.actions.githubusercontent.com:sub" = local.github_subjects.foundation
    })
    lab = merge(local.github_trust_common, {
      "token.actions.githubusercontent.com:sub" = local.github_subjects.lab
    })
    image = merge(local.github_trust_common, {
      "token.actions.githubusercontent.com:sub" = local.github_subjects.image
    })
  }

  role_trust_policies = {
    foundation = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Sid       = "GitHubOidc"
          Effect    = "Allow"
          Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
          Action    = "sts:AssumeRoleWithWebIdentity"
          Condition = { StringEquals = local.github_role_trust.foundation }
        },
        local.approved_local_principals_statements.foundation,
      ]
    })
    lab = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Sid       = "GitHubOidc"
          Effect    = "Allow"
          Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
          Action    = "sts:AssumeRoleWithWebIdentity"
          Condition = { StringEquals = local.github_role_trust.lab }
        },
        local.approved_local_principals_statements.lab,
      ]
    })
    image = jsonencode({
      Version = "2012-10-17"
      Statement = [{
        Sid       = "GitHubOidc"
        Effect    = "Allow"
        Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
        Action    = "sts:AssumeRoleWithWebIdentity"
        Condition = { StringEquals = local.github_role_trust.image }
      }]
    })
    dataset = jsonencode({
      Version   = "2012-10-17"
      Statement = [local.approved_local_principals_statements.dataset]
    })
  }

  role_names = {
    foundation = "airbob-foundation-admin"
    lab        = "airbob-lab-operator"
    image      = "airbob-image-publisher"
    dataset    = "airbob-dataset-publisher"
  }

  dataset_snapshot_write_segment = coalesce(var.dataset_snapshot_writer_release, "__disabled__")
  dataset_snapshot_write_resource = (
    "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/releases/${local.dataset_snapshot_write_segment}/*"
  )
  dataset_snapshot_lock_name = "airbob-dataset-snapshot/${local.dataset_snapshot_write_segment}"
  dataset_snapshot_seal_key = (
    var.dataset_snapshot_writer_release == null
    ? ""
    : "elasticsearch/seals/${var.dataset_snapshot_writer_release}.json"
  )
  dataset_snapshot_seal_resource = (
    "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/seals/${local.dataset_snapshot_write_segment}.json"
  )
  dataset_snapshot_seal_read_resource = (
    "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/seals/*"
  )

  foundation_admin_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "FoundationState"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "arn:aws:s3:::${var.state_bucket_name}/${local.state_keys.foundation}"
      },
      {
        Sid      = "FoundationStateLock"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::${var.state_bucket_name}/${local.state_keys.foundation}.tflock"
      },
      {
        Sid      = "FoundationStateList"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = "arn:aws:s3:::${var.state_bucket_name}"
        Condition = {
          StringLike = {
            "s3:prefix" = [local.state_keys.foundation, "${local.state_keys.foundation}.tflock"]
          }
        }
      },
      {
        Sid      = "StateBucketPostureRead"
        Effect   = "Allow"
        Action   = local.state_bucket_posture_read_actions
        Resource = "arn:aws:s3:::${var.state_bucket_name}"
      },
      {
        Sid      = "BootstrapStateRead"
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = "arn:aws:s3:::${var.state_bucket_name}/${local.state_keys.bootstrap}"
      },
      {
        Sid    = "ManagedBucketRead"
        Effect = "Allow"
        Action = [
          "s3:GetBucket*",
          "s3:GetEncryptionConfiguration",
          "s3:GetLifecycleConfiguration",
          "s3:GetReplicationConfiguration",
          "s3:ListBucket",
        ]
        Resource = [for bucket in aws_s3_bucket.managed : bucket.arn]
      },
      {
        Sid    = "ManagedBucketConfiguration"
        Effect = "Allow"
        Action = [
          "s3:PutBucketOwnershipControls",
          "s3:PutBucketPolicy",
          "s3:PutBucketPublicAccessBlock",
          "s3:PutBucketTagging",
          "s3:PutBucketVersioning",
          "s3:PutEncryptionConfiguration",
          "s3:PutLifecycleConfiguration",
        ]
        Resource = [for bucket in aws_s3_bucket.managed : bucket.arn]
      },
      {
        Sid      = "ApplicationBucketIdentityOnly"
        Effect   = "Allow"
        Action   = ["s3:GetBucketLocation", "s3:ListBucket"]
        Resource = data.aws_s3_bucket.application.arn
      },
      {
        Sid    = "ManagedRepositoryRead"
        Effect = "Allow"
        Action = [
          "ecr:DescribeImages",
          "ecr:DescribeRepositories",
          "ecr:GetLifecyclePolicy",
          "ecr:GetRepositoryPolicy",
          "ecr:ListImages",
          "ecr:ListTagsForResource",
        ]
        Resource = local.all_ecr_repository_arns
      },
      {
        Sid    = "ManagedRepositoryConfiguration"
        Effect = "Allow"
        Action = [
          "ecr:PutImageScanningConfiguration",
          "ecr:PutImageTagMutability",
          "ecr:PutLifecyclePolicy",
          "ecr:TagResource",
          "ecr:UntagResource",
        ]
        Resource = local.all_ecr_repository_arns
      },
      {
        Sid    = "FoundationIdentityReadOnly"
        Effect = "Allow"
        Action = ["iam:GetOpenIDConnectProvider", "iam:GetPolicy", "iam:GetPolicyVersion", "iam:GetRole", "iam:GetRolePolicy", "iam:ListAttachedRolePolicies", "iam:ListPolicyTags", "iam:ListPolicyVersions", "iam:ListRolePolicies", "iam:ListRoleTags"]
        Resource = [
          aws_iam_openid_connect_provider.github.arn,
          "arn:aws:iam::${var.account_id}:role/${local.role_names.foundation}",
          "arn:aws:iam::${var.account_id}:role/${local.role_names.lab}",
          "arn:aws:iam::${var.account_id}:role/${local.role_names.image}",
          "arn:aws:iam::${var.account_id}:role/${local.role_names.dataset}",
          "arn:aws:iam::${var.account_id}:role/${local.dns_controller_role_name}",
          aws_iam_role.expiry_observer.arn,
          aws_iam_policy.lab_host_boundary.arn,
        ]
      },
      {
        Sid    = "ExpiryObserverLambdaReadOnly"
        Effect = "Allow"
        Action = [
          "lambda:GetFunction",
          "lambda:GetFunctionCodeSigningConfig",
          "lambda:GetPolicy",
          "lambda:ListVersionsByFunction",
        ]
        Resource = aws_lambda_function.expiry_observer.arn
      },
      {
        Sid      = "ExpiryObserverEventRuleReadOnly"
        Effect   = "Allow"
        Action   = ["events:DescribeRule", "events:ListTagsForResource", "events:ListTargetsByRule"]
        Resource = aws_cloudwatch_event_rule.expiry_observer.arn
      },
      {
        Sid    = "ExpiryObserverAlarmReadOnly"
        Effect = "Allow"
        Action = ["cloudwatch:DescribeAlarms", "cloudwatch:ListTagsForResource"]
        Resource = concat(
          [for alarm in values(aws_cloudwatch_metric_alarm.expiry_observer) : alarm.arn],
          [aws_cloudwatch_metric_alarm.expiry_observer_errors.arn],
        )
      },
      {
        Sid      = "ExpiryObserverTopicReadOnly"
        Effect   = "Allow"
        Action   = ["sns:GetSubscriptionAttributes", "sns:GetTopicAttributes", "sns:ListSubscriptionsByTopic", "sns:ListTagsForResource"]
        Resource = aws_sns_topic.expiry_alerts.arn
      },
      {
        Sid      = "ExpiryObserverLogGroupReadOnly"
        Effect   = "Allow"
        Action   = "logs:ListTagsForResource"
        Resource = aws_cloudwatch_log_group.expiry_observer.arn
      },
      {
        Sid      = "ExpiryObserverLogDiscovery"
        Effect   = "Allow"
        Action   = "logs:DescribeLogGroups"
        Resource = "*"
      },
      {
        Sid      = "ExpiryObserverKeyReadOnly"
        Effect   = "Allow"
        Action   = ["kms:DescribeKey", "kms:GetKeyPolicy", "kms:GetKeyRotationStatus", "kms:ListResourceTags"]
        Resource = aws_kms_key.expiry_alerts.arn
      },
      {
        Sid    = "LeaseTableReadAndConfigure"
        Effect = "Allow"
        Action = [
          "dynamodb:DescribeContinuousBackups",
          "dynamodb:DescribeTable",
          "dynamodb:DescribeTimeToLive",
          "dynamodb:ListTagsOfResource",
          "dynamodb:TagResource",
          "dynamodb:UntagResource",
          "dynamodb:UpdateContinuousBackups",
          "dynamodb:UpdateTable",
        ]
        Resource = aws_dynamodb_table.orchestration_lease.arn
      },
      {
        Sid    = "PublicZoneReadAndTag"
        Effect = "Allow"
        Action = [
          "route53:ChangeTagsForResource",
          "route53:GetHostedZone",
          "route53:ListResourceRecordSets",
          "route53:ListTagsForResource",
        ]
        Resource = aws_route53_zone.public.arn
      },
      {
        Sid    = "PrivateDnsFoundationRead"
        Effect = "Allow"
        Action = [
          "ec2:DescribeTags",
          "ec2:DescribeVpcAttribute",
          "ec2:DescribeVpcs",
        ]
        Resource = "*"
      },
      {
        Sid    = "PrivateZoneReadAndTag"
        Effect = "Allow"
        Action = [
          "route53:ChangeTagsForResource",
          "route53:GetHostedZone",
          "route53:ListResourceRecordSets",
          "route53:ListTagsForResource",
        ]
        Resource = aws_route53_zone.private.arn
      },
      {
        Sid      = "PublicZoneRecordsConfigure"
        Effect   = "Allow"
        Action   = "route53:ChangeResourceRecordSets"
        Resource = aws_route53_zone.public.arn
        Condition = {
          "ForAllValues:StringEquals" = {
            "route53:ChangeResourceRecordSetsNormalizedRecordNames" = local.foundation_dns_record_names
            "route53:ChangeResourceRecordSetsRecordTypes"           = local.foundation_dns_record_types
            "route53:ChangeResourceRecordSetsActions"               = ["CREATE", "UPSERT", "DELETE"]
          }
        }
      },
      {
        Sid    = "ApiCertificateReadAndTag"
        Effect = "Allow"
        Action = [
          "acm:AddTagsToCertificate",
          "acm:DescribeCertificate",
          "acm:ListTagsForCertificate",
          "acm:RemoveTagsFromCertificate",
        ]
        Resource = aws_acm_certificate.api.arn
      },
      {
        Sid    = "FoundationContractsReadAndConfigure"
        Effect = "Allow"
        Action = [
          "ssm:AddTagsToResource",
          "ssm:GetParameter",
          "ssm:ListTagsForResource",
          "ssm:PutParameter",
          "ssm:RemoveTagsFromResource",
        ]
        Resource = [
          aws_ssm_parameter.dns_consumer_contract.arn,
          aws_ssm_parameter.lab_consumer_contract.arn,
        ]
      },
      {
        Sid      = "FoundationReadDiscovery"
        Effect   = "Allow"
        Action   = ["acm:ListCertificates", "iam:ListOpenIDConnectProviders", "iam:ListRoles", "route53:GetChange", "route53:ListHostedZones", "route53:ListHostedZonesByName", "ssm:DescribeParameters"]
        Resource = "*"
      },
    ]
  })

  lab_operator_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "OperationalState"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = [for state_key in local.lab_operator_state_keys : "arn:aws:s3:::${var.state_bucket_name}/${state_key}"]
      },
      {
        Sid      = "OperationalStateLocks"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = [for state_key in local.lab_operator_state_keys : "arn:aws:s3:::${var.state_bucket_name}/${state_key}.tflock"]
      },
      {
        Sid      = "OperationalStateList"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = "arn:aws:s3:::${var.state_bucket_name}"
        Condition = {
          StringLike = {
            "s3:prefix" = flatten([
              for state_key in local.lab_operator_state_keys : [state_key, "${state_key}.tflock"]
            ])
          }
        }
      },
      {
        Sid      = "StateBucketPostureRead"
        Effect   = "Allow"
        Action   = local.state_bucket_posture_read_actions
        Resource = "arn:aws:s3:::${var.state_bucket_name}"
      },
      {
        Sid      = "BootstrapStateRead"
        Effect   = "Allow"
        Action   = "s3:GetObject"
        Resource = "arn:aws:s3:::${var.state_bucket_name}/${local.state_keys.bootstrap}"
      },
      {
        Sid    = "ReadDatasetAndBundles"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = [
          "${aws_s3_bucket.managed["dataset"].arn}/*",
          "${aws_s3_bucket.managed["bundle"].arn}/*",
        ]
      },
      {
        Sid    = "ListDatasetAndBundles"
        Effect = "Allow"
        Action = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = [
          aws_s3_bucket.managed["dataset"].arn,
          aws_s3_bucket.managed["bundle"].arn,
        ]
      },
      {
        Sid      = "WriteTaggedEvidence"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:PutObjectTagging"]
        Resource = "${aws_s3_bucket.managed["evidence"].arn}/*"
        Condition = {
          StringEquals = {
            "s3:RequestObjectTag/Retention" = ["raw", "summary"]
          }
        }
      },
      {
        Sid    = "ReadOperatorEvidence"
        Effect = "Allow"
        Action = "s3:GetObject"
        Resource = [
          "${aws_s3_bucket.managed["evidence"].arn}/runs/*/operator.json",
          "${aws_s3_bucket.managed["evidence"].arn}/measurements/*",
        ]
      },
      {
        Sid      = "AbortEvidenceMultipartUpload"
        Effect   = "Allow"
        Action   = "s3:AbortMultipartUpload"
        Resource = "${aws_s3_bucket.managed["evidence"].arn}/*"
      },
      {
        Sid      = "ListEvidence"
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:GetBucketLocation"]
        Resource = aws_s3_bucket.managed["evidence"].arn
      },
      {
        Sid      = "PullImages"
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer", "ecr:DescribeImages"]
        Resource = local.all_ecr_repository_arns
      },
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid      = "OrchestrationLease"
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:UpdateItem", "dynamodb:DescribeTable"]
        Resource = aws_dynamodb_table.orchestration_lease.arn
      },
      {
        Sid      = "ReadFoundationContracts"
        Effect   = "Allow"
        Action   = "ssm:GetParameter"
        Resource = [aws_ssm_parameter.dns_consumer_contract.arn, aws_ssm_parameter.lab_consumer_contract.arn]
      },
      {
        Sid      = "ReadApiOriginRecords"
        Effect   = "Allow"
        Action   = "route53:ListResourceRecordSets"
        Resource = aws_route53_zone.public.arn
      },
      {
        Sid      = "ReadRoute53Changes"
        Effect   = "Allow"
        Action   = "route53:GetChange"
        Resource = "arn:aws:route53:::change/*"
      },
      {
        Sid    = "ReadLabLoadBalancer"
        Effect = "Allow"
        Action = [
          "elasticloadbalancing:DescribeLoadBalancerAttributes",
          "elasticloadbalancing:DescribeLoadBalancers",
          "elasticloadbalancing:DescribeTags",
        ]
        Resource = "*"
      },
      {
        Sid      = "AssumeDnsController"
        Effect   = "Allow"
        Action   = ["sts:AssumeRole", "sts:TagSession"]
        Resource = aws_iam_role.dns_controller.arn
      },
      {
        Sid      = "ReadExpiryObserverStatus"
        Effect   = "Allow"
        Action   = "events:DescribeRule"
        Resource = aws_cloudwatch_event_rule.expiry_observer.arn
      },
      {
        Sid      = "ReadExpiryAlarmStatus"
        Effect   = "Allow"
        Action   = "cloudwatch:DescribeAlarms"
        Resource = "*"
      },
      {
        Sid      = "ReadLabTagInventory"
        Effect   = "Allow"
        Action   = "tag:GetResources"
        Resource = "*"
      },
    ]
  })

  image_publisher_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid      = "PublishImmutableImages"
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:CompleteLayerUpload", "ecr:GetDownloadUrlForLayer", "ecr:InitiateLayerUpload", "ecr:PutImage", "ecr:UploadLayerPart"]
        Resource = local.all_ecr_repository_arns
      },
      {
        Sid      = "PublishImmutableServiceBundles"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.managed["bundle"].arn}/service-bundles/*"
      },
    ]
  })

  dataset_publisher_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "DatasetBucketLocation"
        Effect   = "Allow"
        Action   = "s3:GetBucketLocation"
        Resource = aws_s3_bucket.managed["dataset"].arn
      },
      {
        Sid    = "InspectOwnDatasetPublisherRole"
        Effect = "Allow"
        Action = ["iam:GetRole", "iam:GetRolePolicy", "iam:ListAttachedRolePolicies", "iam:ListRolePolicies"]
        Resource = (
          "arn:aws:iam::${var.account_id}:role/${local.role_names.dataset}"
        )
      },
      {
        Sid      = "OwnDatasetSnapshotLease"
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:UpdateItem"]
        Resource = aws_dynamodb_table.orchestration_lease.arn
        Condition = {
          "ForAllValues:StringEquals" = {
            "dynamodb:LeadingKeys" = [local.dataset_snapshot_lock_name]
          }
        }
      },
      {
        Sid      = "ListDatasetReleasePrefixes"
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:ListBucketVersions"]
        Resource = aws_s3_bucket.managed["dataset"].arn
        Condition = {
          StringLike = {
            "s3:prefix" = ["datasets/*", "elasticsearch/releases/*"]
          }
        }
      },
      {
        Sid      = "ListElasticsearchMultipartUploads"
        Effect   = "Allow"
        Action   = "s3:ListBucketMultipartUploads"
        Resource = aws_s3_bucket.managed["dataset"].arn
      },
      {
        Sid    = "ReadPublishedDatasetBytes"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = [
          "${aws_s3_bucket.managed["dataset"].arn}/datasets/*",
          "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/releases/*",
          local.dataset_snapshot_seal_read_resource,
        ]
      },
      {
        Sid      = "WriteImmutableDatasetRelease"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.managed["dataset"].arn}/datasets/*"
        Condition = {
          StringEquals = {
            "s3:if-none-match" = "*"
          }
          StringEqualsIfExists = {
            "s3:x-amz-server-side-encryption" = "AES256"
          }
        }
      },
      {
        Sid      = "WriteDatasetMultipartParts"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = "${aws_s3_bucket.managed["dataset"].arn}/datasets/*"
        Condition = {
          Bool = {
            "s3:ObjectCreationOperation" = "false"
          }
        }
      },
      {
        Sid      = "ManageDatasetMultipartUploads"
        Effect   = "Allow"
        Action   = ["s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]
        Resource = "${aws_s3_bucket.managed["dataset"].arn}/datasets/*"
      },
      {
        Sid      = "WriteElasticsearchSnapshotRepository"
        Effect   = "Allow"
        Action   = ["s3:PutObject", "s3:PutObjectAcl"]
        Resource = local.dataset_snapshot_write_resource
        Condition = {
          StringEqualsIfExists = {
            "s3:x-amz-acl"                    = "bucket-owner-full-control"
            "s3:x-amz-server-side-encryption" = "AES256"
          }
        }
      },
      {
        Sid      = "WriteElasticsearchMultipartParts"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = local.dataset_snapshot_write_resource
        Condition = {
          Bool = {
            "s3:ObjectCreationOperation" = "false"
          }
        }
      },
      {
        Sid      = "ManageElasticsearchSnapshotRepository"
        Effect   = "Allow"
        Action   = ["s3:AbortMultipartUpload", "s3:DeleteObject", "s3:ListMultipartUploadParts"]
        Resource = local.dataset_snapshot_write_resource
      },
      {
        Sid      = "SealElasticsearchSnapshotRelease"
        Effect   = "Allow"
        Action   = "s3:PutObject"
        Resource = local.dataset_snapshot_seal_resource
        Condition = {
          StringEquals = {
            "s3:if-none-match" = "*"
          }
          StringEqualsIfExists = {
            "s3:x-amz-server-side-encryption" = "AES256"
          }
        }
      },
    ]
  })
}

resource "aws_iam_role" "foundation_admin" {
  name                 = local.role_names.foundation
  assume_role_policy   = local.role_trust_policies.foundation
  max_session_duration = 7200

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role" "lab_operator" {
  name                 = local.role_names.lab
  assume_role_policy   = local.role_trust_policies.lab
  max_session_duration = 7200

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role" "image_publisher" {
  name                 = local.role_names.image
  assume_role_policy   = local.role_trust_policies.image
  max_session_duration = 7200

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role" "dataset_publisher" {
  name                 = local.role_names.dataset
  assume_role_policy   = local.role_trust_policies.dataset
  max_session_duration = 7200

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy" "foundation_admin" {
  name   = "airbob-foundation-admin"
  role   = aws_iam_role.foundation_admin.id
  policy = local.foundation_admin_policy

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_policy" "lab_operator" {
  for_each = local.lab_operator_managed_policies

  name   = each.value.name
  policy = each.value.document

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy_attachment" "lab_operator" {
  for_each = local.lab_operator_managed_policies

  role       = aws_iam_role.lab_operator.name
  policy_arn = aws_iam_policy.lab_operator[each.key].arn

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy" "image_publisher" {
  name   = "airbob-image-publisher"
  role   = aws_iam_role.image_publisher.id
  policy = local.image_publisher_policy

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy" "dataset_publisher" {
  name   = "airbob-dataset-publisher"
  role   = aws_iam_role.dataset_publisher.id
  policy = local.dataset_publisher_policy

  depends_on = [data.aws_s3_objects.dataset_snapshot_seal_apply]

  lifecycle {
    prevent_destroy = true
  }
}
