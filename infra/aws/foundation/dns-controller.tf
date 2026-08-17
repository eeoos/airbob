locals {
  dns_controller_role_name = "airbob-dns-controller"

  dns_controller_trust_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "LabOperatorOnly"
      Effect    = "Allow"
      Principal = { AWS = "arn:aws:iam::${var.account_id}:role/${local.role_names.lab}" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
      Condition = {
        "ForAllValues:StringEquals" = {
          "aws:TagKeys" = ["Command", "FencingToken", "RunId"]
        }
        Null = {
          "aws:RequestTag/Command"      = "false"
          "aws:RequestTag/FencingToken" = "false"
          "aws:RequestTag/RunId"        = "false"
        }
      }
    }]
  })

  dns_controller_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "DnsState"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "arn:aws:s3:::${var.state_bucket_name}/${local.state_keys.dns}"
      },
      {
        Sid      = "DnsStateLock"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "arn:aws:s3:::${var.state_bucket_name}/${local.state_keys.dns}.tflock"
      },
      {
        Sid      = "DnsStateList"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = "arn:aws:s3:::${var.state_bucket_name}"
        Condition = {
          StringLike = {
            "s3:prefix" = [local.state_keys.dns, "${local.state_keys.dns}.tflock"]
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
        Sid      = "ReadDnsContract"
        Effect   = "Allow"
        Action   = "ssm:GetParameter"
        Resource = aws_ssm_parameter.dns_consumer_contract.arn
      },
      {
        Sid      = "ReadLease"
        Effect   = "Allow"
        Action   = ["dynamodb:DescribeTable", "dynamodb:GetItem"]
        Resource = aws_dynamodb_table.orchestration_lease.arn
      },
      {
        Sid      = "ChangeApiARecords"
        Effect   = "Allow"
        Action   = "route53:ChangeResourceRecordSets"
        Resource = aws_route53_zone.public.arn
        Condition = {
          "ForAllValues:StringEquals" = {
            "route53:ChangeResourceRecordSetsNormalizedRecordNames" = [local.api_fqdn]
            "route53:ChangeResourceRecordSetsRecordTypes"           = ["A"]
            "route53:ChangeResourceRecordSetsActions"               = ["CREATE", "UPSERT", "DELETE"]
          }
        }
      },
      {
        Sid      = "ReadApiDnsAndChanges"
        Effect   = "Allow"
        Action   = ["route53:GetHostedZone", "route53:GetChange", "route53:ListResourceRecordSets"]
        Resource = [aws_route53_zone.public.arn, "arn:aws:route53:::change/*"]
      },
      {
        Sid      = "ReadLabLoadBalancer"
        Effect   = "Allow"
        Action   = ["elasticloadbalancing:DescribeLoadBalancerAttributes", "elasticloadbalancing:DescribeLoadBalancers", "elasticloadbalancing:DescribeTags"]
        Resource = "*"
      },
    ]
  })
}

resource "aws_iam_role" "dns_controller" {
  name                 = local.dns_controller_role_name
  assume_role_policy   = local.dns_controller_trust_policy
  max_session_duration = 3600

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy" "dns_controller" {
  name   = "airbob-dns-controller"
  role   = aws_iam_role.dns_controller.id
  policy = local.dns_controller_policy

  lifecycle {
    prevent_destroy = true
  }
}
