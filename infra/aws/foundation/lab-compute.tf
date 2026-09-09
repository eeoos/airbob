locals {
  lab_host_boundary_name = "airbob-performance-lab-host-boundary"
  lab_host_boundary_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "SsmManagedInstanceCore"
        Effect = "Allow"
        Action = [
          "ec2messages:AcknowledgeMessage",
          "ec2messages:DeleteMessage",
          "ec2messages:FailMessage",
          "ec2messages:GetEndpoint",
          "ec2messages:GetMessages",
          "ec2messages:SendReply",
          "ssm:DescribeAssociation",
          "ssm:GetDeployablePatchSnapshotForInstance",
          "ssm:GetDocument",
          "ssm:GetManifest",
          "ssm:GetParameter",
          "ssm:GetParameters",
          "ssm:ListAssociations",
          "ssm:ListInstanceAssociations",
          "ssm:PutComplianceItems",
          "ssm:PutConfigurePackageResult",
          "ssm:UpdateAssociationStatus",
          "ssm:UpdateInstanceAssociationStatus",
          "ssm:UpdateInstanceInformation",
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel",
        ]
        Resource = "*"
      },
      {
        Sid    = "ReadImmutableRuntimeInputs"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:GetObjectVersion"]
        Resource = [
          "${aws_s3_bucket.managed["bundle"].arn}/service-bundles/*",
          "${aws_s3_bucket.managed["dataset"].arn}/datasets/*",
          "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/*",
          "${aws_s3_bucket.managed["evidence"].arn}/measurement-inputs/*",
        ]
      },
      {
        Sid      = "DatasetSnapshotBucketLocation"
        Effect   = "Allow"
        Action   = "s3:GetBucketLocation"
        Resource = aws_s3_bucket.managed["dataset"].arn
      },
      {
        Sid      = "ListDatasetSnapshotRepository"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.managed["dataset"].arn
        Condition = {
          StringLike = {
            "s3:prefix" = ["elasticsearch/*"]
          }
        }
      },
      {
        Sid      = "ReadLabDebeziumSecret"
        Effect   = "Allow"
        Action   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue"]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:airbob/lab-*/debezium-*"
      },
      {
        Sid      = "ReadLabRdsManagedMasterSecret"
        Effect   = "Allow"
        Action   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue"]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:rds!db-*"
        Condition = {
          StringLike = {
            "aws:ResourceTag/aws:secretsmanager:owningService" = "rds"
            "aws:ResourceTag/aws:rds:primaryDBInstanceArn"     = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:airbob-lab-*"
          }
        }
      },
      {
        Sid      = "WriteLabDebeziumSecret"
        Effect   = "Allow"
        Action   = "secretsmanager:PutSecretValue"
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:airbob/lab-*/debezium-*"
      },
      {
        Sid      = "ProbeEvidenceBucketLocation"
        Effect   = "Allow"
        Action   = "s3:GetBucketLocation"
        Resource = aws_s3_bucket.managed["evidence"].arn
      },
      {
        Sid      = "PullApprovedImages"
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:DescribeImages", "ecr:GetDownloadUrlForLayer"]
        Resource = local.all_ecr_repository_arns
      },
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Sid    = "WriteBootstrapEvidence"
        Effect = "Allow"
        Action = ["s3:PutObject", "s3:PutObjectTagging"]
        Resource = [
          "${aws_s3_bucket.managed["evidence"].arn}/phase2/*",
          "${aws_s3_bucket.managed["evidence"].arn}/data-bootstrap/*",
          "${aws_s3_bucket.managed["evidence"].arn}/measurements/*",
        ]
        Condition = {
          StringEquals = {
            "s3:RequestObjectTag/Retention" = ["raw", "summary"]
          }
        }
      },
      {
        Sid      = "DenyHostAuthoritativeEvidenceWrites"
        Effect   = "Deny"
        Action   = ["s3:PutObject", "s3:PutObjectTagging"]
        Resource = local.authoritative_evidence_resources
      },
      {
        Sid    = "MonitoringReadOnly"
        Effect = "Allow"
        Action = [
          "cloudwatch:GetMetricData",
          "cloudwatch:GetMetricStatistics",
          "cloudwatch:ListMetrics",
          "ec2:DescribeInstances",
          "ec2:DescribeTags",
        ]
        Resource = "*"
      },
    ]
  })

  lab_ephemeral_request_tag_condition = {
    StringEquals = {
      "aws:RequestTag/Project"     = "airbob"
      "aws:RequestTag/Environment" = "performance-lab"
      "aws:RequestTag/Stack"       = "lab"
      "aws:RequestTag/ManagedBy"   = "terraform"
      "aws:RequestTag/Persistence" = "ephemeral"
    }
    Null = {
      "aws:RequestTag/ExpiresAt"    = "false"
      "aws:RequestTag/FencingToken" = "false"
      "aws:RequestTag/RunId"        = "false"
    }
  }

  lab_ephemeral_resource_tag_condition = {
    StringEquals = {
      "aws:ResourceTag/Project"     = "airbob"
      "aws:ResourceTag/Environment" = "performance-lab"
      "aws:ResourceTag/Stack"       = "lab"
      "aws:ResourceTag/ManagedBy"   = "terraform"
      "aws:ResourceTag/Persistence" = "ephemeral"
    }
    Null = {
      "aws:ResourceTag/ExpiresAt"    = "false"
      "aws:ResourceTag/FencingToken" = "false"
      "aws:ResourceTag/RunId"        = "false"
    }
  }

  lab_ec2_create_tag_keys = [
    "Environment",
    "ExpiresAt",
    "FencingToken",
    "ManagedBy",
    "Monitoring",
    "Name",
    "Persistence",
    "Project",
    "RunId",
    "RuntimeRevision",
    "Service",
    "Stack",
    "Tier",
  ]

  lab_ephemeral_create_tag_condition = merge(
    local.lab_ephemeral_request_tag_condition,
    {
      Null = merge(
        local.lab_ephemeral_request_tag_condition.Null,
        { "aws:RequestTag/Service" = "false" },
      )
      "ForAllValues:StringEquals" = {
        "aws:TagKeys" = [
          "Environment",
          "ExpiresAt",
          "FencingToken",
          "ManagedBy",
          "Persistence",
          "Project",
          "RunId",
          "Service",
          "Stack",
        ]
      }
    },
  )

  lab_ephemeral_tag_binding_condition = {
    StringEquals = merge(
      local.lab_ephemeral_request_tag_condition.StringEquals,
      local.lab_ephemeral_resource_tag_condition.StringEquals,
      {
        "aws:RequestTag/ExpiresAt"    = "$${aws:ResourceTag/ExpiresAt}"
        "aws:RequestTag/FencingToken" = "$${aws:ResourceTag/FencingToken}"
        "aws:RequestTag/RunId"        = "$${aws:ResourceTag/RunId}"
        "aws:RequestTag/Service"      = "$${aws:ResourceTag/Service}"
      },
    )
    Null = merge(
      local.lab_ephemeral_request_tag_condition.Null,
      local.lab_ephemeral_resource_tag_condition.Null,
      {
        "aws:RequestTag/Service"  = "false"
        "aws:ResourceTag/Service" = "false"
      },
    )
    "ForAllValues:StringEquals" = local.lab_ephemeral_create_tag_condition["ForAllValues:StringEquals"]
  }

  lab_asg_tag_binding_condition = merge(
    local.lab_ephemeral_tag_binding_condition,
    {
      "ForAllValues:StringEquals" = {
        "aws:TagKeys" = [
          "Environment",
          "ExpiresAt",
          "FencingToken",
          "ManagedBy",
          "Monitoring",
          "Name",
          "Persistence",
          "Project",
          "RunId",
          "RuntimeRevision",
          "Service",
          "Stack",
        ]
      }
    },
  )

  lab_elb_create_tag_condition = {
    StringEquals = merge(
      local.lab_ephemeral_request_tag_condition.StringEquals,
      {
        "aws:RequestTag/Service" = ["alb", "app"]
      },
    )
    Null = merge(
      local.lab_ephemeral_request_tag_condition.Null,
      { "aws:RequestTag/Service" = "false" },
    )
    "ForAllValues:StringEquals" = {
      "aws:TagKeys" = [
        "Environment",
        "ExpiresAt",
        "FencingToken",
        "ManagedBy",
        "Name",
        "Persistence",
        "Project",
        "RunId",
        "Service",
        "Stack",
      ]
    }
  }

  lab_elb_resource_tag_condition = {
    StringEquals = local.lab_ephemeral_resource_tag_condition.StringEquals
    Null = merge(
      local.lab_ephemeral_resource_tag_condition.Null,
      { "aws:ResourceTag/Service" = "false" },
    )
  }

  lab_elb_listener_create_condition = {
    StringEquals = merge(
      local.lab_ephemeral_request_tag_condition.StringEquals,
      local.lab_ephemeral_resource_tag_condition.StringEquals,
      {
        "aws:RequestTag/Service"  = "alb"
        "aws:ResourceTag/Service" = "alb"
      },
    )
    Null = merge(
      local.lab_ephemeral_request_tag_condition.Null,
      local.lab_ephemeral_resource_tag_condition.Null,
      {
        "aws:RequestTag/Service"                = "false"
        "aws:ResourceTag/Service"               = "false"
        "elasticloadbalancing:ListenerProtocol" = "false"
        "elasticloadbalancing:SecurityPolicy"   = "false"
      },
    )
    "ForAllValues:StringEquals" = merge(
      local.lab_elb_create_tag_condition["ForAllValues:StringEquals"],
      {
        "elasticloadbalancing:ListenerProtocol" = "HTTPS"
        "elasticloadbalancing:SecurityPolicy"   = "ELBSecurityPolicy-TLS13-1-2-2021-06"
      },
    )
  }

  lab_elb_listener_modify_condition = {
    StringEquals = local.lab_ephemeral_resource_tag_condition.StringEquals
    Null = merge(
      local.lab_ephemeral_resource_tag_condition.Null,
      {
        "aws:ResourceTag/Service"               = "false"
        "elasticloadbalancing:ListenerProtocol" = "false"
        "elasticloadbalancing:SecurityPolicy"   = "false"
      },
    )
    "ForAllValues:StringEquals" = {
      "elasticloadbalancing:ListenerProtocol" = "HTTPS"
      "elasticloadbalancing:SecurityPolicy"   = "ELBSecurityPolicy-TLS13-1-2-2021-06"
    }
  }

  lab_rds_request_tag_condition = local.lab_ephemeral_create_tag_condition

  lab_compute_ec2_iam_statements = [
    {
      Sid    = "DescribeLabInfrastructure"
      Effect = "Allow"
      Action = [
        "ec2:Describe*",
        "iam:GetInstanceProfile",
        "iam:GetRole",
        "iam:GetRolePolicy",
        "iam:ListAttachedRolePolicies",
        "iam:ListInstanceProfiles",
        "iam:ListInstanceProfilesForRole",
        "iam:ListRolePolicies",
        "iam:ListRoles",
        "iam:ListRoleTags",
        "tag:GetResources",
      ]
      Resource = "*"
    },
    {
      Sid    = "MutateTaggedEc2Lab"
      Effect = "Allow"
      Action = [
        "ec2:AssociateAddress",
        "ec2:AssociateRouteTable",
        "ec2:AttachInternetGateway",
        "ec2:AuthorizeSecurityGroupEgress",
        "ec2:AuthorizeSecurityGroupIngress",
        "ec2:CreateLaunchTemplateVersion",
        "ec2:CreateRoute",
        "ec2:DeleteRoute",
        "ec2:DisassociateAddress",
        "ec2:DisassociateRouteTable",
        "ec2:ModifyLaunchTemplate",
        "ec2:RevokeSecurityGroupEgress",
        "ec2:RevokeSecurityGroupIngress",
      ]
      Resource = "*"
      Condition = {
        StringEquals = {
          "aws:ResourceTag/Project"     = "airbob"
          "aws:ResourceTag/Environment" = "performance-lab"
          "aws:ResourceTag/Stack"       = "lab"
          "aws:ResourceTag/ManagedBy"   = "terraform"
          "aws:ResourceTag/Persistence" = "ephemeral"
        }
        Null = {
          "aws:ResourceTag/ExpiresAt"    = "false"
          "aws:ResourceTag/FencingToken" = "false"
          "aws:ResourceTag/RunId"        = "false"
        }
      }
    },
    {
      Sid    = "CreateTaggedSecurityGroupRules"
      Effect = "Allow"
      Action = [
        "ec2:AuthorizeSecurityGroupEgress",
        "ec2:AuthorizeSecurityGroupIngress",
      ]
      Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:security-group-rule/*"
      Condition = {
        StringEquals = {
          "aws:RequestTag/Project"     = "airbob"
          "aws:RequestTag/Environment" = "performance-lab"
          "aws:RequestTag/Stack"       = "lab"
          "aws:RequestTag/ManagedBy"   = "terraform"
          "aws:RequestTag/Persistence" = "ephemeral"
        }
        Null = {
          "aws:RequestTag/ExpiresAt"    = "false"
          "aws:RequestTag/FencingToken" = "false"
          "aws:RequestTag/RunId"        = "false"
        }
      }
    },
    {
      Sid      = "TagEc2LabOnCreate"
      Effect   = "Allow"
      Action   = "ec2:CreateTags"
      Resource = "*"
      Condition = {
        StringEquals = {
          "ec2:CreateAction" = [
            "AllocateAddress",
            "AuthorizeSecurityGroupEgress",
            "AuthorizeSecurityGroupIngress",
            "CreateInternetGateway",
            "CreateLaunchTemplate",
            "CreateRouteTable",
            "CreateSecurityGroup",
            "CreateSubnet",
            "CreateVpc",
            "CreateVpcEndpoint",
            "RunInstances",
          ]
          "aws:RequestTag/Project"     = "airbob"
          "aws:RequestTag/Environment" = "performance-lab"
          "aws:RequestTag/Stack"       = "lab"
          "aws:RequestTag/ManagedBy"   = "terraform"
          "aws:RequestTag/Persistence" = "ephemeral"
        }
        Null = {
          "aws:RequestTag/ExpiresAt"    = "false"
          "aws:RequestTag/FencingToken" = "false"
          "aws:RequestTag/RunId"        = "false"
        }
      }
    },
    {
      Sid      = "TagVerifiedProbe"
      Effect   = "Allow"
      Action   = "ec2:CreateTags"
      Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*"
      Condition = {
        StringEquals = {
          "aws:ResourceTag/Project"     = "airbob"
          "aws:ResourceTag/Environment" = "performance-lab"
          "aws:ResourceTag/Stack"       = "lab"
          "aws:ResourceTag/ManagedBy"   = "terraform"
          "aws:ResourceTag/Persistence" = "ephemeral"
        }
        "ForAllValues:StringEquals" = {
          "aws:TagKeys" = ["AirbobEgressVerified", "AirbobEgressVerifiedAt"]
        }
      }
    },
    {
      Sid    = "DestroyTaggedEc2Lab"
      Effect = "Allow"
      Action = [
        "ec2:DeleteInternetGateway",
        "ec2:DeleteLaunchTemplate",
        "ec2:DeleteLaunchTemplateVersions",
        "ec2:DeleteRouteTable",
        "ec2:DeleteSecurityGroup",
        "ec2:DeleteSubnet",
        "ec2:DeleteVpc",
        "ec2:DeleteVpcEndpoints",
        "ec2:DeleteVolume",
        "ec2:DetachInternetGateway",
        "ec2:ReleaseAddress",
        "ec2:TerminateInstances",
      ]
      Resource  = "*"
      Condition = local.lab_ephemeral_resource_tag_condition
    },
    {
      Sid      = "CreateBoundedHostRoles"
      Effect   = "Allow"
      Action   = "iam:CreateRole"
      Resource = "arn:aws:iam::${var.account_id}:role/airbob-lab-host-*"
      Condition = merge(
        local.lab_ephemeral_create_tag_condition,
        {
          ArnEquals = {
            "iam:PermissionsBoundary" = aws_iam_policy.lab_host_boundary.arn
          }
        },
      )
    },
    {
      Sid       = "TagNewBoundedHostRoleOnCreate"
      Effect    = "Allow"
      Action    = "iam:TagRole"
      Resource  = "arn:aws:iam::${var.account_id}:role/airbob-lab-host-*"
      Condition = local.lab_ephemeral_tag_binding_condition
    },
    {
      Sid    = "ManageBoundedHostRoleConfiguration"
      Effect = "Allow"
      Action = [
        "iam:DeleteRole",
        "iam:DeleteRolePolicy",
        "iam:PutRolePolicy",
      ]
      Resource = "arn:aws:iam::${var.account_id}:role/airbob-lab-host-*"
      Condition = {
        ArnEquals = {
          "iam:PermissionsBoundary" = aws_iam_policy.lab_host_boundary.arn
        }
      }
    },
    {
      Sid    = "ManageBoundedHostRoleSsmAttachment"
      Effect = "Allow"
      Action = [
        "iam:AttachRolePolicy",
        "iam:DetachRolePolicy",
      ]
      Resource = "arn:aws:iam::${var.account_id}:role/airbob-lab-host-*"
      Condition = {
        ArnEquals = {
          "iam:PermissionsBoundary" = aws_iam_policy.lab_host_boundary.arn
          "iam:PolicyARN"           = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
        }
      }
    },
    {
      Sid       = "CreateTaggedLabInstanceProfiles"
      Effect    = "Allow"
      Action    = "iam:CreateInstanceProfile"
      Resource  = "arn:aws:iam::${var.account_id}:instance-profile/airbob-lab-host-*"
      Condition = local.lab_ephemeral_create_tag_condition
    },
    {
      Sid       = "TagNewLabInstanceProfileOnCreate"
      Effect    = "Allow"
      Action    = "iam:TagInstanceProfile"
      Resource  = "arn:aws:iam::${var.account_id}:instance-profile/airbob-lab-host-*"
      Condition = local.lab_ephemeral_tag_binding_condition
    },
    {
      Sid    = "ManageLabInstanceProfiles"
      Effect = "Allow"
      Action = [
        "iam:AddRoleToInstanceProfile",
        "iam:DeleteInstanceProfile",
        "iam:RemoveRoleFromInstanceProfile",
      ]
      Resource  = "arn:aws:iam::${var.account_id}:instance-profile/airbob-lab-host-*"
      Condition = local.lab_ephemeral_resource_tag_condition
    },
    {
      Sid      = "PassBoundedRolesToEc2"
      Effect   = "Allow"
      Action   = "iam:PassRole"
      Resource = "arn:aws:iam::${var.account_id}:role/airbob-lab-host-*"
      Condition = {
        StringEquals = {
          "iam:PassedToService" = "ec2.amazonaws.com"
        }
        ArnLike = {
          "iam:AssociatedResourceArn" = "arn:aws:ec2:*:${var.account_id}:instance/*"
        }
      }
    },
    {
      Sid      = "UseSsmCorePolicy"
      Effect   = "Allow"
      Action   = ["iam:GetPolicy", "iam:GetPolicyVersion"]
      Resource = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
    },
  ]

  lab_compute_ssm_dns_statements = [
    {
      Sid       = "CreateTaggedLabDocument"
      Effect    = "Allow"
      Action    = "ssm:CreateDocument"
      Resource  = "arn:aws:ssm:${var.aws_region}:${var.account_id}:document/airbob-lab-*"
      Condition = local.lab_ephemeral_create_tag_condition
    },
    {
      Sid    = "ManageTaggedLabDocument"
      Effect = "Allow"
      Action = [
        "ssm:DeleteDocument",
        "ssm:DescribeDocumentPermission",
        "ssm:UpdateDocument",
        "ssm:UpdateDocumentDefaultVersion",
      ]
      Resource  = "arn:aws:ssm:${var.aws_region}:${var.account_id}:document/airbob-lab-*"
      Condition = local.lab_ephemeral_resource_tag_condition
    },
    {
      Sid       = "CreateTaggedLabAssociation"
      Effect    = "Allow"
      Action    = "ssm:CreateAssociation"
      Resource  = "arn:aws:ssm:${var.aws_region}:${var.account_id}:association/*"
      Condition = local.lab_ephemeral_create_tag_condition
    },
    {
      Sid    = "TagNewLabSsmOnCreate"
      Effect = "Allow"
      Action = "ssm:AddTagsToResource"
      Resource = [
        "arn:aws:ssm:${var.aws_region}:${var.account_id}:association/*",
        "arn:aws:ssm:${var.aws_region}:${var.account_id}:document/airbob-lab-*",
      ]
      Condition = local.lab_ephemeral_tag_binding_condition
    },
    {
      Sid    = "UseLabAssociationTargets"
      Effect = "Allow"
      Action = [
        "ssm:CreateAssociation",
        "ssm:UpdateAssociation",
      ]
      Resource = [
        "arn:aws:ssm:${var.aws_region}:${var.account_id}:document/airbob-lab-*",
        "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*",
      ]
      Condition = local.lab_ephemeral_resource_tag_condition
    },
    {
      Sid    = "ManageTaggedLabAssociation"
      Effect = "Allow"
      Action = [
        "ssm:DeleteAssociation",
        "ssm:UpdateAssociation",
      ]
      Resource  = "arn:aws:ssm:${var.aws_region}:${var.account_id}:association/*"
      Condition = local.lab_ephemeral_resource_tag_condition
    },
    {
      Sid      = "UseRunShellDocument"
      Effect   = "Allow"
      Action   = "ssm:SendCommand"
      Resource = "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript"
    },
    {
      Sid       = "SendCommandsToLabInstances"
      Effect    = "Allow"
      Action    = "ssm:SendCommand"
      Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*"
      Condition = local.lab_ephemeral_resource_tag_condition
    },
    {
      Sid    = "ReadPersistentPrivateZone"
      Effect = "Allow"
      Action = [
        "route53:GetHostedZone",
        "route53:ListResourceRecordSets",
        "route53:ListTagsForResource",
      ]
      Resource = aws_route53_zone.private.arn
    },
    {
      Sid    = "AssociateLabVpcWithPrivateZone"
      Effect = "Allow"
      Action = [
        "route53:AssociateVPCWithHostedZone",
        "route53:DisassociateVPCFromHostedZone",
      ]
      Resource = aws_route53_zone.private.arn
      Condition = {
        StringLike = {
          "route53:VPCs" = "VPCId=vpc-*,VPCRegion=${var.aws_region}"
        }
      }
    },
    {
      Sid      = "ProtectPrivateDnsAnchorAssociation"
      Effect   = "Deny"
      Action   = "route53:DisassociateVPCFromHostedZone"
      Resource = aws_route53_zone.private.arn
      Condition = {
        StringEquals = {
          "route53:VPCs" = "VPCId=${aws_vpc.private_dns_anchor.id},VPCRegion=${var.aws_region}"
        }
      }
    },
    {
      Sid      = "ListPrivateZoneAssociations"
      Effect   = "Allow"
      Action   = "route53:ListHostedZonesByVPC"
      Resource = "*"
      Condition = {
        StringLike = {
          "route53:VPCs" = "VPCId=vpc-*,VPCRegion=${var.aws_region}"
        }
      }
    },
    {
      Sid      = "ChangePrivateServiceRecords"
      Effect   = "Allow"
      Action   = "route53:ChangeResourceRecordSets"
      Resource = aws_route53_zone.private.arn
      Condition = {
        "ForAllValues:StringEquals" = {
          "route53:ChangeResourceRecordSetsNormalizedRecordNames" = [
            "connect.lab.airbob.internal",
            "elasticsearch.lab.airbob.internal",
            "kafka.lab.airbob.internal",
            "monitoring.lab.airbob.internal",
            "redis-cache.lab.airbob.internal",
            "redis-general.lab.airbob.internal",
          ]
          "route53:ChangeResourceRecordSetsRecordTypes" = "A"
          "route53:ChangeResourceRecordSetsActions"     = ["CREATE", "UPSERT", "DELETE"]
        }
      }
    },
    {
      Sid    = "ReadNetworkReceipts"
      Effect = "Allow"
      Action = "s3:GetObject"
      Resource = [
        "${aws_s3_bucket.managed["evidence"].arn}/network-receipts/*",
        "${aws_s3_bucket.managed["evidence"].arn}/network-clearance/*",
      ]
    },
  ]

  lab_safety_mutation_statements = [
    {
      Sid    = "DenyUnexpectedEc2CreateTagKeys"
      Effect = "Deny"
      Action = [
        "ec2:AllocateAddress",
        "ec2:AuthorizeSecurityGroupEgress",
        "ec2:AuthorizeSecurityGroupIngress",
        "ec2:CreateInternetGateway",
        "ec2:CreateLaunchTemplate",
        "ec2:CreateRouteTable",
        "ec2:CreateSecurityGroup",
        "ec2:CreateSubnet",
        "ec2:CreateVpc",
        "ec2:CreateVpcEndpoint",
        "ec2:RunInstances",
      ]
      Resource = "*"
      Condition = {
        "ForAnyValue:StringNotEquals" = {
          "aws:TagKeys" = local.lab_ec2_create_tag_keys
        }
      }
    },
    {
      Sid      = "DenyUnexpectedEc2CreateActionTagKeys"
      Effect   = "Deny"
      Action   = "ec2:CreateTags"
      Resource = "*"
      Condition = {
        StringEquals = {
          "ec2:CreateAction" = [
            "AllocateAddress",
            "AuthorizeSecurityGroupEgress",
            "AuthorizeSecurityGroupIngress",
            "CreateInternetGateway",
            "CreateLaunchTemplate",
            "CreateRouteTable",
            "CreateSecurityGroup",
            "CreateSubnet",
            "CreateVpc",
            "CreateVpcEndpoint",
            "RunInstances",
          ]
        }
        "ForAnyValue:StringNotEquals" = {
          "aws:TagKeys" = local.lab_ec2_create_tag_keys
        }
      }
    },
    {
      Sid    = "DescribeLabApplicationInfrastructure"
      Effect = "Allow"
      Action = [
        "autoscaling:Describe*",
        "cloudwatch:DescribeAlarms",
        "cloudwatch:GetDashboard",
        "cloudwatch:GetMetricData",
        "cloudwatch:GetMetricStatistics",
        "cloudwatch:ListDashboards",
        "cloudwatch:ListMetrics",
        "elasticloadbalancing:Describe*",
      ]
      Resource = "*"
    },
    {
      Sid    = "ReadCommandResults"
      Effect = "Allow"
      Action = [
        "ssm:DescribeAssociation",
        "ssm:DescribeDocument",
        "ssm:DescribeInstanceInformation",
        "ssm:GetCommandInvocation",
        "ssm:GetDocument",
        "ssm:ListAssociationVersions",
        "ssm:ListAssociations",
        "ssm:ListCommandInvocations",
        "ssm:ListCommands",
        "ssm:ListDocuments",
        "ssm:ListTagsForResource",
      ]
      Resource = "*"
    },
    {
      Sid      = "ReadPrivateDnsChanges"
      Effect   = "Allow"
      Action   = "route53:GetChange"
      Resource = "arn:aws:route53:::change/*"
    },
    {
      Sid      = "ModifyLabNatSourceDestCheck"
      Effect   = "Allow"
      Action   = "ec2:ModifyInstanceAttribute"
      Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*"
      Condition = merge(
        local.lab_ephemeral_resource_tag_condition,
        {
          StringEquals = merge(
            local.lab_ephemeral_resource_tag_condition.StringEquals,
            { "ec2:Attribute/SourceDestCheck" = "false" },
          )
        },
      )
    },
    {
      Sid      = "ClearLabInstanceTerminationProtection"
      Effect   = "Allow"
      Action   = "ec2:ModifyInstanceAttribute"
      Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*"
      Condition = merge(
        local.lab_ephemeral_resource_tag_condition,
        {
          StringEquals = merge(
            local.lab_ephemeral_resource_tag_condition.StringEquals,
            { "ec2:Attribute/DisableApiTermination" = "false" },
          )
        },
      )
    },
    {
      Sid      = "ClearLabInstanceStopProtection"
      Effect   = "Allow"
      Action   = "ec2:ModifyInstanceAttribute"
      Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*"
      Condition = merge(
        local.lab_ephemeral_resource_tag_condition,
        {
          StringEquals = merge(
            local.lab_ephemeral_resource_tag_condition.StringEquals,
            { "ec2:Attribute/DisableApiStop" = "false" },
          )
        },
      )
    },
    {
      Sid      = "KeepLabSubnetsPrivate"
      Effect   = "Allow"
      Action   = "ec2:ModifySubnetAttribute"
      Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:subnet/*"
      Condition = merge(
        local.lab_ephemeral_resource_tag_condition,
        {
          StringEquals = merge(
            local.lab_ephemeral_resource_tag_condition.StringEquals,
            { "ec2:Attribute/MapPublicIpOnLaunch" = "false" },
          )
        },
      )
    },
    {
      Sid      = "EnableLabVpcDnsSupport"
      Effect   = "Allow"
      Action   = "ec2:ModifyVpcAttribute"
      Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:vpc/*"
      Condition = merge(
        local.lab_ephemeral_resource_tag_condition,
        {
          StringEquals = merge(
            local.lab_ephemeral_resource_tag_condition.StringEquals,
            { "ec2:Attribute/EnableDnsSupport" = "true" },
          )
        },
      )
    },
    {
      Sid      = "EnableLabVpcDnsHostnames"
      Effect   = "Allow"
      Action   = "ec2:ModifyVpcAttribute"
      Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:vpc/*"
      Condition = merge(
        local.lab_ephemeral_resource_tag_condition,
        {
          StringEquals = merge(
            local.lab_ephemeral_resource_tag_condition.StringEquals,
            { "ec2:Attribute/EnableDnsHostnames" = "true" },
          )
        },
      )
    },
    {
      Sid      = "PutActionlessLabMetricAlarm"
      Effect   = "Allow"
      Action   = "cloudwatch:PutMetricAlarm"
      Resource = "arn:aws:cloudwatch:${var.aws_region}:${var.account_id}:alarm:airbob-lab-*"
      Condition = {
        Null = {
          "cloudwatch:AlarmActions" = "true"
        }
      }
    },
    {
      Sid    = "ManageNamedLabAlarms"
      Effect = "Allow"
      Action = [
        "cloudwatch:DeleteAlarms",
        "cloudwatch:ListTagsForResource",
        "cloudwatch:TagResource",
        "cloudwatch:UntagResource",
      ]
      Resource = "arn:aws:cloudwatch:${var.aws_region}:${var.account_id}:alarm:airbob-lab-*"
    },
    {
      Sid      = "ManageNamedLabDashboard"
      Effect   = "Allow"
      Action   = ["cloudwatch:DeleteDashboards", "cloudwatch:PutDashboard"]
      Resource = "arn:aws:cloudwatch::${var.account_id}:dashboard/airbob-lab-*"
    },
    {
      Sid      = "CreateRequiredServiceLinkedRoles"
      Effect   = "Allow"
      Action   = "iam:CreateServiceLinkedRole"
      Resource = "*"
      Condition = {
        StringEquals = {
          "iam:AWSServiceName" = [
            "autoscaling.amazonaws.com",
            "elasticloadbalancing.amazonaws.com",
            "rds.amazonaws.com",
          ]
        }
      }
    },
  ]

  lab_compute_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      local.lab_compute_ec2_iam_statements,
      local.lab_compute_ssm_dns_statements,
      local.lab_safety_mutation_statements,
    )
  })

  lab_compute_ec2_core_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      for statement in local.lab_compute_ec2_iam_statements : statement
      if !contains([
        "CreateBoundedHostRoles",
        "TagNewBoundedHostRoleOnCreate",
        "TagNewLabInstanceProfileOnCreate",
      ], statement.Sid)
    ]
  })

  lab_compute_ec2_iam_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      local.lab_compute_ec2_iam_statements,
      local.lab_safety_mutation_statements,
    )
  })

  lab_compute_ssm_dns_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      for statement in local.lab_compute_ssm_dns_statements : statement
      if statement.Sid != "TagNewLabSsmOnCreate"
    ]
  })

  lab_safety_mutation_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      for statement in local.lab_safety_mutation_statements : statement
      if statement.Sid != "ReadPrivateDnsChanges"
    ]
  })

  lab_network_core_create_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "AllocateTaggedLabAddress"
        Effect    = "Allow"
        Action    = "ec2:AllocateAddress"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:elastic-ip/*"
        Condition = local.lab_ephemeral_request_tag_condition
      },
      {
        Sid       = "CreateTaggedLabInternetGateway"
        Effect    = "Allow"
        Action    = "ec2:CreateInternetGateway"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:internet-gateway/*"
        Condition = local.lab_ephemeral_request_tag_condition
      },
      {
        Sid       = "CreateTaggedLabLaunchTemplate"
        Effect    = "Allow"
        Action    = "ec2:CreateLaunchTemplate"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:launch-template/*"
        Condition = local.lab_ephemeral_request_tag_condition
      },
      {
        Sid       = "CreateTaggedLabVpc"
        Effect    = "Allow"
        Action    = "ec2:CreateVpc"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:vpc/*"
        Condition = local.lab_ephemeral_request_tag_condition
      },
      {
        Sid       = "CreateTaggedLabSubnet"
        Effect    = "Allow"
        Action    = "ec2:CreateSubnet"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:subnet/*"
        Condition = local.lab_ephemeral_request_tag_condition
      },
      {
        Sid       = "UseTaggedLabVpcForSubnet"
        Effect    = "Allow"
        Action    = "ec2:CreateSubnet"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:vpc/*"
        Condition = local.lab_ephemeral_resource_tag_condition
      },
      {
        Sid       = "UseTaggedLabVpcForRouteTable"
        Effect    = "Allow"
        Action    = "ec2:CreateRouteTable"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:vpc/*"
        Condition = local.lab_ephemeral_resource_tag_condition
      },
      {
        Sid      = "CreateTaggedLabS3VpcEndpoint"
        Effect   = "Allow"
        Action   = "ec2:CreateVpcEndpoint"
        Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:vpc-endpoint/*"
        Condition = {
          StringEquals = merge(
            local.lab_ephemeral_request_tag_condition.StringEquals,
            { "ec2:VpceServiceName" = "com.amazonaws.${var.aws_region}.s3" },
          )
          Null = local.lab_ephemeral_request_tag_condition.Null
        }
      },
      ], [
      for statement in local.lab_compute_ssm_dns_statements : statement
      if statement.Sid == "TagNewLabSsmOnCreate"
      ], [
      for statement in local.lab_app_compute_statements : statement
      if contains([
        "ModifyNamedLabHttpsListeners",
      ], statement.Sid)
    ])
  })

  lab_network_dependent_create_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "CreateTaggedLabRouteTable"
        Effect    = "Allow"
        Action    = "ec2:CreateRouteTable"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:route-table/*"
        Condition = local.lab_ephemeral_request_tag_condition
      },
      {
        Sid       = "CreateTaggedLabSecurityGroup"
        Effect    = "Allow"
        Action    = "ec2:CreateSecurityGroup"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:security-group/*"
        Condition = local.lab_ephemeral_request_tag_condition
      },
      {
        Sid       = "UseTaggedLabVpcForSecurityGroup"
        Effect    = "Allow"
        Action    = "ec2:CreateSecurityGroup"
        Resource  = "arn:aws:ec2:${var.aws_region}:${var.account_id}:vpc/*"
        Condition = local.lab_ephemeral_resource_tag_condition
      },
      {
        Sid    = "UseTaggedLabNetworkForS3VpcEndpoint"
        Effect = "Allow"
        Action = "ec2:CreateVpcEndpoint"
        Resource = [
          "arn:aws:ec2:${var.aws_region}:${var.account_id}:route-table/*",
          "arn:aws:ec2:${var.aws_region}:${var.account_id}:vpc/*",
        ]
        Condition = local.lab_ephemeral_resource_tag_condition
      },
      ], [
      for statement in local.lab_app_compute_statements : statement
      if contains([
        "CreateTaggedLabLoadBalancing",
        "TagNewLabLoadBalancingOnCreate",
        "CreateTaggedLabHttpsListener",
        "ManageNamedLabTargetGroups",
      ], statement.Sid)
    ])
  })

  lab_run_instances_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "UseReviewedMachineImage"
        Effect   = "Allow"
        Action   = "ec2:RunInstances"
        Resource = "arn:aws:ec2:${var.aws_region}::image/ami-00b5b2470beafd65f"
      },
      {
        Sid      = "LaunchTaggedLabInstances"
        Effect   = "Allow"
        Action   = "ec2:RunInstances"
        Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*"
        Condition = {
          StringEquals = {
            "aws:RequestTag/Project"     = "airbob"
            "aws:RequestTag/Environment" = "performance-lab"
            "aws:RequestTag/Stack"       = "lab"
            "aws:RequestTag/ManagedBy"   = "terraform"
            "aws:RequestTag/Persistence" = "ephemeral"
            "ec2:Tenancy"                = "default"
            "ec2:InstanceType" = [
              "c6i.large",
              "c6i.xlarge",
              "t3.medium",
              "t3.micro",
              "t3.nano",
              "t3.small",
            ]
          }
          Null = {
            "aws:RequestTag/ExpiresAt"    = "false"
            "aws:RequestTag/FencingToken" = "false"
            "aws:RequestTag/RunId"        = "false"
          }
          "ForAllValues:StringEquals" = {
            "aws:TagKeys" = [
              "Environment",
              "ExpiresAt",
              "FencingToken",
              "ManagedBy",
              "Monitoring",
              "Name",
              "Persistence",
              "Project",
              "RunId",
              "RuntimeRevision",
              "Service",
              "Stack",
            ]
          }
        }
      },
      {
        Sid      = "CreateTaggedLabRootVolumes"
        Effect   = "Allow"
        Action   = "ec2:RunInstances"
        Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:volume/*"
        Condition = {
          StringEquals = {
            "aws:RequestTag/Project"     = "airbob"
            "aws:RequestTag/Environment" = "performance-lab"
            "aws:RequestTag/Stack"       = "lab"
            "aws:RequestTag/ManagedBy"   = "terraform"
            "aws:RequestTag/Persistence" = "ephemeral"
            "ec2:VolumeType"             = "gp3"
          }
          Null = {
            "aws:RequestTag/ExpiresAt"    = "false"
            "aws:RequestTag/FencingToken" = "false"
            "aws:RequestTag/RunId"        = "false"
          }
          NumericLessThanEquals = {
            "ec2:VolumeIops"       = 3000
            "ec2:VolumeSize"       = 40
            "ec2:VolumeThroughput" = 125
          }
          Bool = {
            "ec2:Encrypted" = "true"
          }
          "ForAllValues:StringEquals" = {
            "aws:TagKeys" = [
              "Environment",
              "ExpiresAt",
              "FencingToken",
              "ManagedBy",
              "Monitoring",
              "Name",
              "Persistence",
              "Project",
              "RunId",
              "RuntimeRevision",
              "Service",
              "Stack",
            ]
          }
        }
      },
      {
        Sid    = "UseTaggedLabInstanceDependencies"
        Effect = "Allow"
        Action = "ec2:RunInstances"
        Resource = [
          "arn:aws:ec2:${var.aws_region}:${var.account_id}:launch-template/*",
          "arn:aws:ec2:${var.aws_region}:${var.account_id}:security-group/*",
          "arn:aws:ec2:${var.aws_region}:${var.account_id}:subnet/*",
        ]
        Condition = {
          StringEquals = {
            "aws:ResourceTag/Project"     = "airbob"
            "aws:ResourceTag/Environment" = "performance-lab"
            "aws:ResourceTag/Stack"       = "lab"
            "aws:ResourceTag/ManagedBy"   = "terraform"
            "aws:ResourceTag/Persistence" = "ephemeral"
          }
          Null = {
            "aws:ResourceTag/ExpiresAt"    = "false"
            "aws:ResourceTag/FencingToken" = "false"
            "aws:ResourceTag/RunId"        = "false"
          }
        }
      },
      {
        Sid      = "CreatePrimaryNetworkInterfaces"
        Effect   = "Allow"
        Action   = "ec2:RunInstances"
        Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:network-interface/*"
      },
      {
        Sid      = "DisassociateAddressFromPrimaryEni"
        Effect   = "Allow"
        Action   = "ec2:DisassociateAddress"
        Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:network-interface/*"
      },
      ], [
      for statement in local.lab_compute_ec2_iam_statements : statement
      if contains([
        "CreateBoundedHostRoles",
        "TagNewBoundedHostRoleOnCreate",
        "TagNewLabInstanceProfileOnCreate",
      ], statement.Sid)
      ], [
      for statement in local.lab_safety_mutation_statements : statement
      if statement.Sid == "ReadPrivateDnsChanges"
    ])
  })

  lab_rds_provision_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "CreateTaggedLabDbParameterGroup"
        Effect    = "Allow"
        Action    = "rds:CreateDBParameterGroup"
        Resource  = "arn:aws:rds:${var.aws_region}:${var.account_id}:pg:airbob-lab-*"
        Condition = local.lab_rds_request_tag_condition
      },
      {
        Sid       = "CreateTaggedLabDbSubnetGroup"
        Effect    = "Allow"
        Action    = "rds:CreateDBSubnetGroup"
        Resource  = "arn:aws:rds:${var.aws_region}:${var.account_id}:subgrp:airbob-lab-*"
        Condition = local.lab_rds_request_tag_condition
      },
      {
        Sid      = "CreateBoundedDumpLabDbInstance"
        Effect   = "Allow"
        Action   = "rds:CreateDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:airbob-lab-*"
        Condition = {
          StringEquals = merge(
            local.lab_ephemeral_request_tag_condition.StringEquals,
            {
              "rds:DatabaseClass"  = "db.t3.small"
              "rds:DatabaseEngine" = "mysql"
            },
          )
          Null = local.lab_ephemeral_create_tag_condition.Null
          Bool = {
            "rds:ManageMasterUserPassword" = "true"
            "rds:PubliclyAccessible"       = "false"
            "rds:StorageEncrypted"         = "true"
            "rds:Vpc"                      = "true"
          }
          BoolIfExists = {
            "rds:MultiAz" = "false"
          }
          NumericEquals = {
            "rds:Piops" = 3000
          }
          NumericLessThanEqualsIfExists = {
            "rds:StorageSize" = 100
          }
          "ForAllValues:StringEquals" = local.lab_rds_request_tag_condition["ForAllValues:StringEquals"]
        }
      },
      {
        Sid      = "UseDefaultOptionGroupForDumpLabDb"
        Effect   = "Allow"
        Action   = "rds:CreateDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:og:default:mysql-8-0"
      },
      {
        Sid    = "UseRunBoundConfigurationForDumpLabDb"
        Effect = "Allow"
        Action = "rds:CreateDBInstance"
        Resource = [
          "arn:aws:rds:${var.aws_region}:${var.account_id}:pg:airbob-$${aws:RequestTag/RunId}",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:subgrp:airbob-$${aws:RequestTag/RunId}",
        ]
      },
      ], [for statement in [
        {
          Sid      = "RestoreBoundedSnapshotLabDbInstance"
          Effect   = "Allow"
          Action   = "rds:RestoreDBInstanceFromDBSnapshot"
          Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:airbob-lab-*"
          Condition = {
            StringEquals = merge(
              local.lab_ephemeral_request_tag_condition.StringEquals,
              {
                "rds:DatabaseClass"  = "db.t3.small"
                "rds:DatabaseEngine" = "mysql"
              },
            )
            Null = local.lab_ephemeral_create_tag_condition.Null
            Bool = {
              "rds:PubliclyAccessible" = "false"
            }
            BoolIfExists = {
              "rds:MultiAz"          = "false"
              "rds:StorageEncrypted" = "true"
              "rds:Vpc"              = "true"
            }
            NumericEqualsIfExists = {
              "rds:Piops" = 3000
            }
            NumericLessThanEqualsIfExists = {
              "rds:StorageSize" = 100
            }
            "ForAllValues:StringEquals" = local.lab_rds_request_tag_condition["ForAllValues:StringEquals"]
          }
        },
        {
          Sid      = "UseApprovedSnapshotForRestoreLabDb"
          Effect   = "Allow"
          Action   = "rds:RestoreDBInstanceFromDBSnapshot"
          Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:snapshot:${var.approved_rds_snapshot_identifier}"
        },
        {
          Sid      = "UseDefaultOptionGroupForRestoreLabDb"
          Effect   = "Allow"
          Action   = "rds:RestoreDBInstanceFromDBSnapshot"
          Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:og:default:mysql-8-0"
        },
        {
          Sid    = "UseRunBoundConfigurationForRestoreLabDb"
          Effect = "Allow"
          Action = "rds:RestoreDBInstanceFromDBSnapshot"
          Resource = [
            "arn:aws:rds:${var.aws_region}:${var.account_id}:pg:airbob-$${aws:RequestTag/RunId}",
            "arn:aws:rds:${var.aws_region}:${var.account_id}:subgrp:airbob-$${aws:RequestTag/RunId}",
          ]
        },
    ] : statement if var.approved_rds_snapshot_identifier != ""])
  })

  lab_data_compute_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DescribeLabRds"
        Effect = "Allow"
        Action = [
          "rds:DescribeDBInstances",
          "rds:DescribeDBParameterGroups",
          "rds:DescribeDBParameters",
          "rds:DescribeDBSnapshots",
          "rds:DescribeDBSubnetGroups",
          "rds:ListTagsForResource",
          "secretsmanager:ListSecrets",
        ]
        Resource = "*"
      },
      {
        Sid    = "TagNewLabRdsOnCreate"
        Effect = "Allow"
        Action = "rds:AddTagsToResource"
        Resource = [
          "arn:aws:rds:${var.aws_region}:${var.account_id}:db:airbob-lab-*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:pg:airbob-lab-*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:subgrp:airbob-lab-*",
        ]
        Condition = local.lab_ephemeral_tag_binding_condition
      },
      {
        Sid    = "ManageTaggedLabRdsInstance"
        Effect = "Allow"
        Action = [
          "rds:DeleteDBInstance",
          "rds:ModifyDBInstance",
          "rds:RebootDBInstance",
        ]
        Resource  = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:*"
        Condition = local.lab_ephemeral_resource_tag_condition
      },
      {
        Sid    = "ManageTaggedLabRdsConfiguration"
        Effect = "Allow"
        Action = [
          "rds:DeleteDBParameterGroup",
          "rds:DeleteDBSubnetGroup",
          "rds:ModifyDBParameterGroup",
          "rds:ModifyDBSubnetGroup",
          "rds:ResetDBParameterGroup",
        ]
        Resource = [
          "arn:aws:rds:${var.aws_region}:${var.account_id}:pg:airbob-lab-*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:subgrp:airbob-lab-*",
        ]
        Condition = local.lab_ephemeral_resource_tag_condition
      },
      {
        Sid      = "DenyUnboundedLabRdsClassChange"
        Effect   = "Deny"
        Action   = "rds:ModifyDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:*"
        Condition = {
          Null = {
            "rds:DatabaseClass" = "false"
          }
          StringNotEquals = {
            "rds:DatabaseClass" = "db.t3.small"
          }
        }
      },
      {
        Sid      = "DenyNonMysqlLabRdsEngineChange"
        Effect   = "Deny"
        Action   = "rds:ModifyDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:*"
        Condition = {
          Null = {
            "rds:DatabaseEngine" = "false"
          }
          StringNotEquals = {
            "rds:DatabaseEngine" = "mysql"
          }
        }
      },
      {
        Sid      = "DenyMultiAzLabRdsChange"
        Effect   = "Deny"
        Action   = "rds:ModifyDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:*"
        Condition = {
          Bool = {
            "rds:MultiAz" = "true"
          }
        }
      },
      {
        Sid      = "DenyUnencryptedLabRdsChange"
        Effect   = "Deny"
        Action   = "rds:ModifyDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:*"
        Condition = {
          Bool = {
            "rds:StorageEncrypted" = "false"
          }
        }
      },
      {
        Sid      = "DenyUnmanagedMasterPasswordChange"
        Effect   = "Deny"
        Action   = "rds:ModifyDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:*"
        Condition = {
          Bool = {
            "rds:ManageMasterUserPassword" = "false"
          }
        }
      },
      {
        Sid      = "DenyAboveBaselineIopsLabRdsChange"
        Effect   = "Deny"
        Action   = "rds:ModifyDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:*"
        Condition = {
          NumericGreaterThan = {
            "rds:Piops" = 3000
          }
        }
      },
      {
        Sid      = "DenyOversizedLabRdsChange"
        Effect   = "Deny"
        Action   = "rds:ModifyDBInstance"
        Resource = "arn:aws:rds:${var.aws_region}:${var.account_id}:db:*"
        Condition = {
          NumericGreaterThan = {
            "rds:StorageSize" = 100
          }
        }
      },
      {
        Sid    = "ManageEphemeralLabSecretMetadata"
        Effect = "Allow"
        Action = [
          "secretsmanager:CreateSecret",
          "secretsmanager:DeleteSecret",
          "secretsmanager:DescribeSecret",
          "secretsmanager:GetResourcePolicy",
          "secretsmanager:TagResource",
          "secretsmanager:UntagResource",
        ]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:airbob/lab-*/debezium-*"
      },
      {
        Sid    = "CreateRdsManagedMasterSecret"
        Effect = "Allow"
        Action = [
          "secretsmanager:CreateSecret",
          "secretsmanager:TagResource",
        ]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:rds!db-*"
      },
      {
        Sid      = "DescribeSecretsManagerKey"
        Effect   = "Allow"
        Action   = "kms:DescribeKey"
        Resource = "*"
      },
    ]
  })

  lab_app_compute_statements = [
    {
      Sid      = "CreateTaggedLabAutoScaling"
      Effect   = "Allow"
      Action   = "autoscaling:CreateAutoScalingGroup"
      Resource = "arn:aws:autoscaling:${var.aws_region}:${var.account_id}:autoScalingGroup:*:autoScalingGroupName/airbob-lab-*"
      Condition = {
        StringEquals = {
          "aws:RequestTag/Project"     = "airbob"
          "aws:RequestTag/Environment" = "performance-lab"
          "aws:RequestTag/Stack"       = "lab"
          "aws:RequestTag/ManagedBy"   = "terraform"
          "aws:RequestTag/Persistence" = "ephemeral"
        }
        Null = {
          "aws:RequestTag/ExpiresAt"            = "false"
          "aws:RequestTag/FencingToken"         = "false"
          "aws:RequestTag/RunId"                = "false"
          "aws:RequestTag/Service"              = "false"
          "autoscaling:LaunchConfigurationName" = "true"
        }
        Bool = {
          "autoscaling:LaunchTemplateVersionSpecified" = "true"
        }
        NumericLessThanEquals = {
          "autoscaling:MaxSize" = 4
        }
        "ForAllValues:StringEquals" = {
          "aws:TagKeys" = [
            "Environment",
            "ExpiresAt",
            "FencingToken",
            "ManagedBy",
            "Monitoring",
            "Name",
            "Persistence",
            "Project",
            "RunId",
            "RuntimeRevision",
            "Service",
            "Stack",
          ]
        }
        "ForAllValues:ArnLike" = {
          "autoscaling:TargetGroupARNs" = "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:targetgroup/airbob-lab-*/*"
        }
        StringEqualsIfExists = {
          "autoscaling:InstanceTypes" = ["c6i.large"]
        }
      }
    },
    {
      Sid       = "TagNewLabAutoScalingOnCreate"
      Effect    = "Allow"
      Action    = "autoscaling:CreateOrUpdateTags"
      Resource  = "arn:aws:autoscaling:${var.aws_region}:${var.account_id}:autoScalingGroup:*:autoScalingGroupName/airbob-lab-*"
      Condition = local.lab_asg_tag_binding_condition
    },
    {
      Sid    = "CreateTaggedLabLoadBalancing"
      Effect = "Allow"
      Action = ["elasticloadbalancing:CreateLoadBalancer", "elasticloadbalancing:CreateTargetGroup"]
      Resource = [
        "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:loadbalancer/app/airbob-lab-*/*",
        "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:targetgroup/airbob-lab-*/*",
      ]
      Condition = local.lab_elb_create_tag_condition
    },
    {
      Sid    = "TagNewLabLoadBalancingOnCreate"
      Effect = "Allow"
      Action = "elasticloadbalancing:AddTags"
      Resource = [
        "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:loadbalancer/app/airbob-lab-*/*",
        "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:listener/app/airbob-lab-*/*/*",
        "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:targetgroup/airbob-lab-*/*",
      ]
      Condition = merge(
        local.lab_elb_create_tag_condition,
        {
          StringEquals = merge(
            local.lab_elb_create_tag_condition.StringEquals,
            {
              "elasticloadbalancing:CreateAction" = [
                "CreateListener",
                "CreateLoadBalancer",
                "CreateTargetGroup",
              ]
            },
          )
        },
      )
    },
    {
      Sid       = "CreateTaggedLabHttpsListener"
      Effect    = "Allow"
      Action    = "elasticloadbalancing:CreateListener"
      Resource  = "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:loadbalancer/app/airbob-lab-*/*"
      Condition = local.lab_elb_listener_create_condition
    },
    {
      Sid    = "ManageNamedLabAutoScaling"
      Effect = "Allow"
      Action = [
        "autoscaling:DeleteAutoScalingGroup",
        "autoscaling:DeletePolicy",
        "autoscaling:DisableMetricsCollection",
        "autoscaling:EnableMetricsCollection",
        "autoscaling:PutScalingPolicy",
        "autoscaling:SetDesiredCapacity",
      ]
      Resource  = "arn:aws:autoscaling:${var.aws_region}:${var.account_id}:autoScalingGroup:*:autoScalingGroupName/airbob-lab-*"
      Condition = local.lab_ephemeral_resource_tag_condition
    },
    {
      Sid    = "ManageLabAutoScalingTargetGroups"
      Effect = "Allow"
      Action = [
        "autoscaling:AttachLoadBalancerTargetGroups",
        "autoscaling:DetachLoadBalancerTargetGroups",
      ]
      Resource = "arn:aws:autoscaling:${var.aws_region}:${var.account_id}:autoScalingGroup:*:autoScalingGroupName/airbob-lab-*"
      Condition = merge(
        local.lab_ephemeral_resource_tag_condition,
        {
          "ForAllValues:ArnLike" = {
            "autoscaling:TargetGroupARNs" = "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:targetgroup/airbob-lab-*/*"
          }
        },
      )
    },
    {
      Sid      = "UpdateLabAutoScalingWithPinnedTemplate"
      Effect   = "Allow"
      Action   = "autoscaling:UpdateAutoScalingGroup"
      Resource = "arn:aws:autoscaling:${var.aws_region}:${var.account_id}:autoScalingGroup:*:autoScalingGroupName/airbob-lab-*"
      Condition = merge(
        local.lab_ephemeral_resource_tag_condition,
        {
          Null = merge(
            local.lab_ephemeral_resource_tag_condition.Null,
            { "autoscaling:LaunchConfigurationName" = "true" },
          )
          BoolIfExists = {
            "autoscaling:LaunchTemplateVersionSpecified" = "true"
          }
          NumericLessThanEqualsIfExists = {
            "autoscaling:MaxSize" = 4
          }
          StringEqualsIfExists = {
            "autoscaling:InstanceTypes" = ["c6i.large"]
          }
        },
      )
    },
    {
      Sid    = "ManageNamedLabLoadBalancers"
      Effect = "Allow"
      Action = [
        "elasticloadbalancing:DeleteLoadBalancer",
        "elasticloadbalancing:ModifyLoadBalancerAttributes",
        "elasticloadbalancing:SetSecurityGroups",
        "elasticloadbalancing:SetSubnets",
      ]
      Resource  = "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:loadbalancer/app/airbob-lab-*/*"
      Condition = local.lab_elb_resource_tag_condition
    },
    {
      Sid    = "ManageNamedLabTargetGroups"
      Effect = "Allow"
      Action = [
        "elasticloadbalancing:DeleteTargetGroup",
        "elasticloadbalancing:ModifyTargetGroup",
        "elasticloadbalancing:ModifyTargetGroupAttributes",
      ]
      Resource  = "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:targetgroup/airbob-lab-*/*"
      Condition = local.lab_elb_resource_tag_condition
    },
    {
      Sid       = "DeleteNamedLabListeners"
      Effect    = "Allow"
      Action    = "elasticloadbalancing:DeleteListener"
      Resource  = "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:listener/app/airbob-lab-*/*/*"
      Condition = local.lab_elb_resource_tag_condition
    },
    {
      Sid       = "ModifyNamedLabHttpsListeners"
      Effect    = "Allow"
      Action    = "elasticloadbalancing:ModifyListener"
      Resource  = "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:listener/app/airbob-lab-*/*/*"
      Condition = local.lab_elb_listener_modify_condition
    },
  ]

  lab_app_compute_core_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      for statement in local.lab_app_compute_statements : statement
      if !contains([
        "CreateTaggedLabLoadBalancing",
        "TagNewLabLoadBalancingOnCreate",
        "CreateTaggedLabHttpsListener",
        "ManageNamedLabTargetGroups",
        "ModifyNamedLabHttpsListeners",
      ], statement.Sid)
    ]
  })

  lab_app_compute_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      local.lab_app_compute_statements,
      local.lab_safety_mutation_statements,
    )
  })
}

resource "aws_iam_policy" "lab_host_boundary" {
  name        = local.lab_host_boundary_name
  description = "Maximum permissions for ephemeral Airbob performance-lab EC2 roles"
  policy      = local.lab_host_boundary_policy

  lifecycle {
    prevent_destroy = true
  }
}
