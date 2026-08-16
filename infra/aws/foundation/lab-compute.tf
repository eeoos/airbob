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
        Sid    = "BootstrapSecrets"
        Effect = "Allow"
        Action = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
        Resource = [
          "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:rds!db-*",
          "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:airbob/*/debezium-*",
        ]
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

  lab_compute_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "DescribeLabInfrastructure"
        Effect = "Allow"
        Action = [
          "ec2:Describe*",
          "iam:GetInstanceProfile",
          "iam:GetRole",
          "iam:GetRolePolicy",
          "iam:ListAttachedRolePolicies",
          "iam:ListInstanceProfilesForRole",
          "iam:ListRolePolicies",
          "iam:ListRoleTags",
          "ssm:DescribeAssociation",
          "ssm:DescribeDocument",
          "ssm:GetDocument",
          "ssm:ListAssociationVersions",
          "ssm:ListAssociations",
          "ssm:ListDocuments",
          "ssm:ListTagsForResource",
        ]
        Resource = "*"
      },
      {
        Sid    = "CreateTaggedEc2Lab"
        Effect = "Allow"
        Action = [
          "ec2:AllocateAddress",
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
        Sid    = "MutateTaggedEc2Lab"
        Effect = "Allow"
        Action = [
          "ec2:AssociateAddress",
          "ec2:AssociateRouteTable",
          "ec2:AttachInternetGateway",
          "ec2:AuthorizeSecurityGroupEgress",
          "ec2:AuthorizeSecurityGroupIngress",
          "ec2:CreateRoute",
          "ec2:CreateLaunchTemplateVersion",
          "ec2:DeleteRoute",
          "ec2:DisassociateAddress",
          "ec2:DisassociateRouteTable",
          "ec2:ModifyInstanceAttribute",
          "ec2:ModifyLaunchTemplate",
          "ec2:ModifySubnetAttribute",
          "ec2:ModifyVpcAttribute",
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
              "CreateInternetGateway",
              "CreateLaunchTemplate",
              "CreateRouteTable",
              "CreateSecurityGroupRule",
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
          "ec2:DetachInternetGateway",
          "ec2:ReleaseAddress",
          "ec2:TerminateInstances",
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
        }
      },
      {
        Sid      = "CreateBoundedHostRoles"
        Effect   = "Allow"
        Action   = ["iam:CreateRole", "iam:TagRole"]
        Resource = "arn:aws:iam::${var.account_id}:role/airbob-lab-host-*"
        Condition = {
          StringEquals = {
            "iam:PermissionsBoundary"    = aws_iam_policy.lab_host_boundary.arn
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
        Sid    = "ManageBoundedHostRoles"
        Effect = "Allow"
        Action = [
          "iam:AttachRolePolicy",
          "iam:DeleteRole",
          "iam:DeleteRolePolicy",
          "iam:DetachRolePolicy",
          "iam:PutRolePolicy",
          "iam:TagRole",
          "iam:UntagRole",
        ]
        Resource = "arn:aws:iam::${var.account_id}:role/airbob-lab-host-*"
      },
      {
        Sid    = "ManageLabInstanceProfiles"
        Effect = "Allow"
        Action = [
          "iam:AddRoleToInstanceProfile",
          "iam:CreateInstanceProfile",
          "iam:DeleteInstanceProfile",
          "iam:RemoveRoleFromInstanceProfile",
          "iam:TagInstanceProfile",
          "iam:UntagInstanceProfile",
        ]
        Resource = "arn:aws:iam::${var.account_id}:instance-profile/airbob-lab-host-*"
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
        }
      },
      {
        Sid      = "UseSsmCorePolicy"
        Effect   = "Allow"
        Action   = ["iam:GetPolicy", "iam:GetPolicyVersion"]
        Resource = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
      },
      {
        Sid      = "CreateTaggedLabDocument"
        Effect   = "Allow"
        Action   = "ssm:CreateDocument"
        Resource = "arn:aws:ssm:${var.aws_region}:${var.account_id}:document/airbob-*"
        Condition = {
          StringEquals = {
            "aws:RequestTag/Project"     = "airbob"
            "aws:RequestTag/Environment" = "performance-lab"
            "aws:RequestTag/Stack"       = "lab"
            "aws:RequestTag/Persistence" = "ephemeral"
          }
        }
      },
      {
        Sid    = "ManageTaggedLabDocument"
        Effect = "Allow"
        Action = [
          "ssm:AddTagsToResource",
          "ssm:DeleteDocument",
          "ssm:RemoveTagsFromResource",
          "ssm:UpdateDocument",
          "ssm:UpdateDocumentDefaultVersion",
        ]
        Resource = "arn:aws:ssm:${var.aws_region}:${var.account_id}:document/airbob-*"
        Condition = {
          StringEquals = {
            "aws:ResourceTag/Project"     = "airbob"
            "aws:ResourceTag/Environment" = "performance-lab"
            "aws:ResourceTag/Stack"       = "lab"
            "aws:ResourceTag/Persistence" = "ephemeral"
          }
        }
      },
      {
        Sid      = "CreateTaggedLabAssociation"
        Effect   = "Allow"
        Action   = "ssm:CreateAssociation"
        Resource = "arn:aws:ssm:${var.aws_region}:${var.account_id}:association/*"
        Condition = {
          StringEquals = {
            "aws:RequestTag/Project"     = "airbob"
            "aws:RequestTag/Environment" = "performance-lab"
            "aws:RequestTag/Stack"       = "lab"
            "aws:RequestTag/Persistence" = "ephemeral"
          }
        }
      },
      {
        Sid    = "UseLabAssociationTargets"
        Effect = "Allow"
        Action = "ssm:CreateAssociation"
        Resource = [
          "arn:aws:ssm:${var.aws_region}:${var.account_id}:document/airbob-*",
          "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*",
        ]
        Condition = {
          StringEquals = {
            "ssm:resourceTag/Project"     = "airbob"
            "ssm:resourceTag/Environment" = "performance-lab"
            "ssm:resourceTag/Stack"       = "lab"
          }
        }
      },
      {
        Sid    = "ManageTaggedLabAssociation"
        Effect = "Allow"
        Action = [
          "ssm:AddTagsToResource",
          "ssm:DeleteAssociation",
          "ssm:RemoveTagsFromResource",
          "ssm:UpdateAssociation",
        ]
        Resource = "arn:aws:ssm:${var.aws_region}:${var.account_id}:association/*"
        Condition = {
          StringEquals = {
            "aws:ResourceTag/Project"     = "airbob"
            "aws:ResourceTag/Environment" = "performance-lab"
            "aws:ResourceTag/Stack"       = "lab"
            "aws:ResourceTag/Persistence" = "ephemeral"
          }
        }
      },
      {
        Sid      = "UseRunShellDocument"
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = "arn:aws:ssm:${var.aws_region}::document/AWS-RunShellScript"
      },
      {
        Sid      = "SendCommandsToLabInstances"
        Effect   = "Allow"
        Action   = "ssm:SendCommand"
        Resource = "arn:aws:ec2:${var.aws_region}:${var.account_id}:instance/*"
        Condition = {
          StringEquals = {
            "ssm:resourceTag/Project"     = "airbob"
            "ssm:resourceTag/Environment" = "performance-lab"
            "ssm:resourceTag/Stack"       = "lab"
          }
        }
      },
      {
        Sid      = "ReadCommandResults"
        Effect   = "Allow"
        Action   = ["ssm:GetCommandInvocation", "ssm:ListCommandInvocations", "ssm:ListCommands"]
        Resource = "*"
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
        Sid      = "ReadPrivateDnsChanges"
        Effect   = "Allow"
        Action   = "route53:GetChange"
        Resource = "arn:aws:route53:::change/*"
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
        ]
        Resource = "*"
      },
      {
        Sid    = "CreateTaggedLabRds"
        Effect = "Allow"
        Action = [
          "rds:CreateDBInstance",
          "rds:CreateDBParameterGroup",
          "rds:CreateDBSubnetGroup",
          "rds:RestoreDBInstanceFromDBSnapshot",
        ]
        Resource = [
          "arn:aws:rds:${var.aws_region}:${var.account_id}:db:airbob-*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:og:*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:pg:airbob-*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:snapshot:airbob-dataset-*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:subgrp:airbob-*",
        ]
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
        Sid    = "ManageTaggedLabRds"
        Effect = "Allow"
        Action = [
          "rds:AddTagsToResource",
          "rds:DeleteDBInstance",
          "rds:DeleteDBParameterGroup",
          "rds:DeleteDBSubnetGroup",
          "rds:ModifyDBInstance",
          "rds:ModifyDBParameterGroup",
          "rds:ModifyDBSubnetGroup",
          "rds:RebootDBInstance",
          "rds:RemoveTagsFromResource",
          "rds:ResetDBParameterGroup",
        ]
        Resource = [
          "arn:aws:rds:${var.aws_region}:${var.account_id}:db:airbob-*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:pg:airbob-*",
          "arn:aws:rds:${var.aws_region}:${var.account_id}:subgrp:airbob-*",
        ]
        Condition = {
          StringEquals = {
            "aws:ResourceTag/Project"     = "airbob"
            "aws:ResourceTag/Environment" = "performance-lab"
            "aws:ResourceTag/Stack"       = "lab"
            "aws:ResourceTag/ManagedBy"   = "terraform"
            "aws:ResourceTag/Persistence" = "ephemeral"
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
          "secretsmanager:TagResource",
          "secretsmanager:UntagResource",
        ]
        Resource = "arn:aws:secretsmanager:${var.aws_region}:${var.account_id}:secret:airbob/*"
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

  lab_app_compute_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
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
        Sid      = "CreateTaggedLabAutoScaling"
        Effect   = "Allow"
        Action   = "autoscaling:CreateAutoScalingGroup"
        Resource = "arn:aws:autoscaling:${var.aws_region}:${var.account_id}:autoScalingGroup:*:autoScalingGroupName/airbob-*"
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
        Sid    = "CreateTaggedLabLoadBalancing"
        Effect = "Allow"
        Action = ["elasticloadbalancing:CreateLoadBalancer", "elasticloadbalancing:CreateTargetGroup"]
        Resource = [
          "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:loadbalancer/app/airbob-*/*",
          "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:targetgroup/airbob-*/*",
        ]
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
        Sid    = "ManageNamedLabAutoScaling"
        Effect = "Allow"
        Action = [
          "autoscaling:AttachLoadBalancerTargetGroups",
          "autoscaling:CancelInstanceRefresh",
          "autoscaling:CreateOrUpdateTags",
          "autoscaling:DeleteAutoScalingGroup",
          "autoscaling:DeletePolicy",
          "autoscaling:DeleteTags",
          "autoscaling:DetachLoadBalancerTargetGroups",
          "autoscaling:DisableMetricsCollection",
          "autoscaling:EnableMetricsCollection",
          "autoscaling:PutScalingPolicy",
          "autoscaling:RollbackInstanceRefresh",
          "autoscaling:SetDesiredCapacity",
          "autoscaling:StartInstanceRefresh",
          "autoscaling:UpdateAutoScalingGroup",
        ]
        Resource = "arn:aws:autoscaling:${var.aws_region}:${var.account_id}:autoScalingGroup:*:autoScalingGroupName/airbob-*"
        Condition = {
          StringEquals = {
            "aws:ResourceTag/Project"     = "airbob"
            "aws:ResourceTag/Environment" = "performance-lab"
            "aws:ResourceTag/Stack"       = "lab"
            "aws:ResourceTag/ManagedBy"   = "terraform"
            "aws:ResourceTag/Persistence" = "ephemeral"
          }
        }
      },
      {
        Sid    = "ManageNamedLabLoadBalancing"
        Effect = "Allow"
        Action = [
          "elasticloadbalancing:AddTags",
          "elasticloadbalancing:CreateListener",
          "elasticloadbalancing:DeleteListener",
          "elasticloadbalancing:DeleteLoadBalancer",
          "elasticloadbalancing:DeleteTargetGroup",
          "elasticloadbalancing:ModifyListener",
          "elasticloadbalancing:ModifyLoadBalancerAttributes",
          "elasticloadbalancing:ModifyTargetGroup",
          "elasticloadbalancing:ModifyTargetGroupAttributes",
          "elasticloadbalancing:RemoveTags",
          "elasticloadbalancing:SetSecurityGroups",
          "elasticloadbalancing:SetSubnets",
        ]
        Resource = [
          "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:loadbalancer/app/airbob-*/*",
          "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:listener/app/airbob-*/*/*",
          "arn:aws:elasticloadbalancing:${var.aws_region}:${var.account_id}:targetgroup/airbob-*/*",
        ]
      },
      {
        Sid    = "ManageNamedLabAlarms"
        Effect = "Allow"
        Action = [
          "cloudwatch:DeleteAlarms",
          "cloudwatch:PutMetricAlarm",
          "cloudwatch:TagResource",
          "cloudwatch:UntagResource",
        ]
        Resource = "arn:aws:cloudwatch:${var.aws_region}:${var.account_id}:alarm:airbob-*"
      },
      {
        Sid      = "ManageNamedLabDashboard"
        Effect   = "Allow"
        Action   = ["cloudwatch:DeleteDashboards", "cloudwatch:PutDashboard"]
        Resource = "arn:aws:cloudwatch::${var.account_id}:dashboard/airbob-*"
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
            ]
          }
        }
      },
    ]
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

resource "aws_iam_role_policy" "lab_compute" {
  name   = "airbob-lab-operator-compute"
  role   = aws_iam_role.lab_operator.id
  policy = local.lab_compute_policy

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy" "lab_data_compute" {
  name   = "airbob-lab-operator-data-compute"
  role   = aws_iam_role.lab_operator.id
  policy = local.lab_data_compute_policy

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy" "lab_app_compute" {
  name   = "airbob-lab-operator-app-compute"
  role   = aws_iam_role.lab_operator.id
  policy = local.lab_app_compute_policy

  lifecycle {
    prevent_destroy = true
  }
}
