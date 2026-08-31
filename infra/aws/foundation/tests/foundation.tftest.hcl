mock_provider "aws" {
  override_data {
    target          = data.aws_caller_identity.current
    override_during = plan
    values = {
      account_id = "942632789808"
      arn        = "arn:aws:iam::942632789808:user/foundation-test"
      user_id    = "AIDATEST"
    }
  }

  override_data {
    target          = data.aws_region.current
    override_during = plan
    values = {
      region = "ap-northeast-2"
    }
  }

  override_data {
    target          = data.aws_s3_bucket.application
    override_during = plan
    values = {
      id     = "airbob-existing-application-assets"
      arn    = "arn:aws:s3:::airbob-existing-application-assets"
      bucket = "airbob-existing-application-assets"
    }
  }

  # Terraform cannot execute an import against a mocked provider. Overriding
  # the declared import target makes the plan exercise the imported contract.
  override_resource {
    target          = aws_ecr_repository.application
    override_during = plan
    values = {
      arn            = "arn:aws:ecr:ap-northeast-2:942632789808:repository/airbob-repo"
      name           = "airbob-repo"
      repository_url = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-repo"
    }
  }

  override_resource {
    target          = aws_iam_openid_connect_provider.github
    override_during = plan
    values = {
      arn = "arn:aws:iam::942632789808:oidc-provider/token.actions.githubusercontent.com"
    }
  }

  override_resource {
    target          = aws_iam_policy.lab_host_boundary
    override_during = plan
    values = {
      arn = "arn:aws:iam::942632789808:policy/airbob-performance-lab-host-boundary"
    }
  }

  override_resource {
    target          = aws_iam_role.dns_controller
    override_during = plan
    values = {
      arn = "arn:aws:iam::942632789808:role/airbob-dns-controller"
      id  = "airbob-dns-controller"
    }
  }

  override_resource {
    target          = aws_route53_zone.public
    override_during = plan
    values = {
      arn          = "arn:aws:route53:::hostedzone/Z0123456789EXAMPLE"
      zone_id      = "Z0123456789EXAMPLE"
      name_servers = ["ns-1.awsdns.example", "ns-2.awsdns.example", "ns-3.awsdns.example", "ns-4.awsdns.example"]
    }
  }

  override_resource {
    target          = aws_vpc.private_dns_anchor
    override_during = plan
    values = {
      id = "vpc-0privateanchor123"
    }
  }

  override_resource {
    target          = aws_route53_zone.private
    override_during = plan
    values = {
      arn     = "arn:aws:route53:::hostedzone/Z0987654321PRIVATE"
      zone_id = "Z0987654321PRIVATE"
      name    = "lab.airbob.internal"
    }
  }

  override_resource {
    target          = aws_acm_certificate.api
    override_during = plan
    values = {
      arn = "arn:aws:acm:ap-northeast-2:942632789808:certificate/11111111-2222-3333-4444-555555555555"
      domain_validation_options = [{
        domain_name           = "api.airbob.cloud"
        resource_record_name  = "_validation.api.airbob.cloud."
        resource_record_type  = "CNAME"
        resource_record_value = "_validation.acm-validations.aws."
      }]
    }
  }

  override_resource {
    target          = aws_s3_bucket.managed
    override_during = plan
    values = {
      arn = "arn:aws:s3:::airbob-mocked-foundation-bucket"
      id  = "airbob-mocked-foundation-bucket"
    }
  }

  override_resource {
    target          = aws_ecr_repository.infrastructure
    override_during = plan
    values = {
      arn            = "arn:aws:ecr:ap-northeast-2:942632789808:repository/airbob-infra/mock"
      repository_url = "942632789808.dkr.ecr.ap-northeast-2.amazonaws.com/airbob-infra/mock"
    }
  }

  override_resource {
    target          = aws_dynamodb_table.orchestration_lease
    override_during = plan
    values = {
      arn  = "arn:aws:dynamodb:ap-northeast-2:942632789808:table/airbob-performance-lab-orchestration-lease"
      name = "airbob-performance-lab-orchestration-lease"
    }
  }

  override_resource {
    target          = aws_ssm_parameter.dns_consumer_contract
    override_during = plan
    values = {
      arn = "arn:aws:ssm:ap-northeast-2:942632789808:parameter/airbob/performance-lab/foundation/dns-contract"
    }
  }

  override_resource {
    target          = aws_ssm_parameter.lab_consumer_contract
    override_during = plan
    values = {
      arn = "arn:aws:ssm:ap-northeast-2:942632789808:parameter/airbob/performance-lab/foundation/lab-contract"
    }
  }

  override_resource {
    target          = aws_cloudwatch_log_group.expiry_observer
    override_during = plan
    values = {
      arn = "arn:aws:logs:ap-northeast-2:942632789808:log-group:/aws/lambda/airbob-performance-lab-expiry-observer"
    }
  }

  override_resource {
    target          = aws_iam_role.expiry_observer
    override_during = plan
    values = {
      arn = "arn:aws:iam::942632789808:role/airbob-performance-lab-expiry-observer"
    }
  }

  override_resource {
    target          = aws_lambda_function.expiry_observer
    override_during = plan
    values = {
      arn           = "arn:aws:lambda:ap-northeast-2:942632789808:function:airbob-performance-lab-expiry-observer"
      function_name = "airbob-performance-lab-expiry-observer"
    }
  }

  override_resource {
    target          = aws_cloudwatch_event_rule.expiry_observer
    override_during = plan
    values = {
      arn  = "arn:aws:events:ap-northeast-2:942632789808:rule/airbob-performance-lab-expiry-observer"
      name = "airbob-performance-lab-expiry-observer"
    }
  }

  override_resource {
    target          = aws_sns_topic.expiry_alerts
    override_during = plan
    values = {
      arn = "arn:aws:sns:ap-northeast-2:942632789808:airbob-performance-lab-expiry-alerts"
    }
  }

  override_resource {
    target          = aws_kms_key.expiry_alerts
    override_during = plan
    values = {
      arn    = "arn:aws:kms:ap-northeast-2:942632789808:key/11111111-2222-3333-4444-555555555555"
      key_id = "11111111-2222-3333-4444-555555555555"
    }
  }

  override_resource {
    target          = aws_cloudwatch_metric_alarm.expiry_observer["action-required"]
    override_during = plan
    values = {
      arn = "arn:aws:cloudwatch:ap-northeast-2:942632789808:alarm:airbob-performance-lab-expiry-action-required"
    }
  }

  override_resource {
    target          = aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"]
    override_during = plan
    values = {
      arn = "arn:aws:cloudwatch:ap-northeast-2:942632789808:alarm:airbob-performance-lab-expiry-heartbeat-missing"
    }
  }

  override_resource {
    target          = aws_cloudwatch_metric_alarm.expiry_observer_errors
    override_during = plan
    values = {
      arn = "arn:aws:cloudwatch:ap-northeast-2:942632789808:alarm:airbob-performance-lab-expiry-lambda-errors"
    }
  }

}

override_data {
  target          = data.aws_s3_objects.dataset_snapshot_seal_plan[0]
  override_during = plan
  values = {
    keys = []
  }
}

override_data {
  target          = data.aws_s3_objects.dataset_snapshot_seal_apply[0]
  override_during = apply
  values = {
    keys = []
  }
}

variables {
  existing_application_bucket_name       = "airbob-existing-application-assets"
  application_ecr_scan_on_push           = false
  dns_inventory_reviewed                 = true
  dnssec_ds_reviewed                     = true
  github_foundation_subject              = "repo:eeoos/airbob:environment:aws-foundation"
  github_lab_subject                     = "repo:eeoos/airbob:environment:aws-performance-lab"
  github_image_subject                   = "repo:eeoos/airbob:environment:aws-image-publisher"
  github_oidc_subjects_reviewed          = true
  foundation_local_principal_arns        = ["arn:aws:iam::942632789808:user/foundation-test"]
  lab_local_principal_arns               = ["arn:aws:iam::942632789808:user/lab-test"]
  dataset_publisher_local_principal_arns = ["arn:aws:iam::942632789808:user/admin-eeoos"]
  dataset_snapshot_writer_release        = "rehearsal-v20"
  local_principal_requires_mfa           = true
  expiry_observer_enabled                = false
  expiry_alert_email                     = null
  expiry_alert_subscription_confirmed    = false

  static_dns_records = {
    apex = {
      name    = "airbob.cloud"
      type    = "A"
      ttl     = 300
      records = ["76.76.21.21"]
    }
    www = {
      name    = "www.airbob.cloud"
      type    = "CNAME"
      ttl     = 300
      records = ["cname.vercel-dns.com."]
    }
  }
}

run "foundation_contract" {
  command = plan

  variables {
    dns_delegation_confirmed            = false
    expiry_observer_enabled             = true
    expiry_alert_email                  = "alerts@example.com"
    expiry_alert_subscription_confirmed = true
  }

  override_resource {
    target          = aws_sns_topic_subscription.expiry_alert_email[0]
    override_during = plan
    values = {
      pending_confirmation = false
    }
  }

  assert {
    condition = (
      toset(keys(aws_s3_bucket.managed)) == toset(["dataset", "evidence", "bundle"]) &&
      aws_s3_bucket.managed["dataset"].bucket == "airbob-performance-lab-dataset-942632789808" &&
      aws_s3_bucket.managed["evidence"].bucket == "airbob-performance-lab-evidence-942632789808" &&
      aws_s3_bucket.managed["bundle"].bucket == "airbob-performance-lab-bundles-942632789808"
    )
    error_message = "The foundation must manage exactly the three deterministic persistent buckets."
  }

  assert {
    condition = alltrue([
      for name, bucket in aws_s3_bucket.managed :
      !bucket.force_destroy &&
      aws_s3_bucket_versioning.managed[name].versioning_configuration[0].status == "Enabled" &&
      one(aws_s3_bucket_server_side_encryption_configuration.managed[name].rule).apply_server_side_encryption_by_default[0].sse_algorithm == "AES256" &&
      aws_s3_bucket_public_access_block.managed[name].block_public_acls &&
      aws_s3_bucket_public_access_block.managed[name].block_public_policy &&
      aws_s3_bucket_public_access_block.managed[name].ignore_public_acls &&
      aws_s3_bucket_public_access_block.managed[name].restrict_public_buckets &&
      one(aws_s3_bucket_ownership_controls.managed[name].rule).object_ownership == "BucketOwnerEnforced"
    ])
    error_message = "Every managed bucket must preserve the encryption, versioning, ownership, and public-access contract."
  }

  assert {
    condition = (
      aws_ecr_repository.application.name == "airbob-repo" &&
      aws_ecr_repository.application.image_tag_mutability == "IMMUTABLE" &&
      !aws_ecr_repository.application.force_delete &&
      !one(aws_ecr_repository.application.image_scanning_configuration).scan_on_push &&
      toset([for repository in aws_ecr_repository.infrastructure : repository.name]) == toset([
        "airbob-infra/redis",
        "airbob-infra/redis-exporter",
        "airbob-infra/node-exporter",
        "airbob-infra/kafka",
        "airbob-infra/debezium",
        "airbob-infra/elasticsearch",
        "airbob-infra/elasticsearch-exporter",
        "airbob-infra/prometheus",
        "airbob-infra/grafana",
      ])
    )
    error_message = "The imported app ECR and exact nine immutable infrastructure repositories must remain separated."
  }

  assert {
    condition = alltrue([
      for repository in aws_ecr_repository.infrastructure :
      repository.image_tag_mutability == "IMMUTABLE" &&
      !repository.force_delete &&
      one(repository.image_scanning_configuration).scan_on_push &&
      one(repository.encryption_configuration).encryption_type == "AES256"
    ])
    error_message = "Every infrastructure ECR repository must be immutable, scanned, and encrypted."
  }

  assert {
    condition = (
      aws_dynamodb_table.orchestration_lease.billing_mode == "PAY_PER_REQUEST" &&
      aws_dynamodb_table.orchestration_lease.hash_key == "LockName" &&
      aws_dynamodb_table.orchestration_lease.deletion_protection_enabled &&
      one(aws_dynamodb_table.orchestration_lease.server_side_encryption).enabled &&
      one(aws_dynamodb_table.orchestration_lease.point_in_time_recovery).enabled
    )
    error_message = "The orchestration lease must retain its protected, encrypted, recoverable PAY_PER_REQUEST fencing-token table."
  }

  assert {
    condition = (
      aws_s3_bucket_lifecycle_configuration.release["dataset"].bucket == aws_s3_bucket.managed["dataset"].id &&
      length(aws_s3_bucket_lifecycle_configuration.release["dataset"].rule) == 1 &&
      one(aws_s3_bucket_lifecycle_configuration.release["dataset"].rule).id == "abort-incomplete-uploads" &&
      one(aws_s3_bucket_lifecycle_configuration.release["dataset"].rule).abort_incomplete_multipart_upload[0].days_after_initiation == 7 &&
      length(one(aws_s3_bucket_lifecycle_configuration.release["dataset"].rule).expiration) == 0 &&
      length(one(aws_s3_bucket_lifecycle_configuration.release["dataset"].rule).noncurrent_version_expiration) == 0
    )
    error_message = "The dataset bucket may only abort incomplete multipart uploads; lifecycle expiration must never target snapshot-release or seal versions/delete markers."
  }

  assert {
    condition = (
      length(aws_s3_bucket_lifecycle_configuration.release["bundle"].rule) == 2 &&
      one([
        for rule in aws_s3_bucket_lifecycle_configuration.release["bundle"].rule : rule
        if rule.id == "expire-noncurrent-versions"
      ]).noncurrent_version_expiration[0].noncurrent_days == 30 &&
      one([
        for rule in aws_s3_bucket_lifecycle_configuration.evidence.rule : rule
        if rule.id == "expire-tagged-raw-evidence"
      ]).expiration[0].days == 30 &&
      one([
        for rule in aws_s3_bucket_lifecycle_configuration.evidence.rule : rule
        if rule.id == "expire-tagged-summary-evidence"
      ]).expiration[0].days == 365
    )
    error_message = "Only explicitly safe bundle and tagged evidence data may have bounded retention."
  }

  assert {
    condition = (
      aws_route53_zone.public.name == "airbob.cloud" &&
      toset(keys(aws_route53_record.static)) == toset(["apex", "www"]) &&
      aws_acm_certificate.api.domain_name == "api.airbob.cloud" &&
      aws_acm_certificate.api.validation_method == "DNS" &&
      length(aws_acm_certificate_validation.api) == 0
    )
    error_message = "The first foundation pass must create the new zone/static inventory/certificate without waiting for delegation."
  }

  assert {
    condition = (
      aws_ssm_parameter.dns_consumer_contract.type == "String" &&
      toset(keys(local.dns_consumer_contract)) == toset([
        "schemaVersion", "zone_id", "zone_name", "api_fqdn",
      ]) &&
      aws_ssm_parameter.lab_consumer_contract.type == "String" &&
      toset(keys(local.lab_consumer_contract)) == toset([
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
        "ecr_repositories",
      ])
    )
    error_message = "Foundation consumers must receive only the exact non-secret SSM contracts."
  }

  assert {
    condition = (
      local.github_role_trust.foundation["token.actions.githubusercontent.com:sub"] == "repo:eeoos/airbob:environment:aws-foundation" &&
      local.github_role_trust.lab["token.actions.githubusercontent.com:sub"] == "repo:eeoos/airbob:environment:aws-performance-lab" &&
      local.github_role_trust.image["token.actions.githubusercontent.com:sub"] == "repo:eeoos/airbob:environment:aws-image-publisher" &&
      alltrue([
        for trust in values(local.github_role_trust) :
        toset(keys(trust)) == toset([
          "token.actions.githubusercontent.com:aud",
          "token.actions.githubusercontent.com:sub",
        ])
      ]) &&
      aws_iam_role.foundation_admin.max_session_duration == 7200 &&
      aws_iam_role.lab_operator.max_session_duration == 7200 &&
      aws_iam_role.image_publisher.max_session_duration == 7200 &&
      aws_iam_role.dataset_publisher.max_session_duration == 7200 &&
      aws_iam_role.dns_controller.max_session_duration == 3600 &&
      one([
        for statement in jsondecode(local.role_trust_policies.foundation).Statement : statement
        if statement.Sid == "ApprovedLocalPrincipals"
      ]).Principal.AWS == ["arn:aws:iam::942632789808:user/foundation-test"] &&
      one([
        for statement in jsondecode(local.role_trust_policies.lab).Statement : statement
        if statement.Sid == "ApprovedLocalPrincipals"
      ]).Principal.AWS == ["arn:aws:iam::942632789808:user/lab-test"] &&
      jsondecode(local.role_trust_policies.dataset).Statement == [{
        Sid       = "ApprovedLocalPrincipals"
        Effect    = "Allow"
        Principal = { AWS = ["arn:aws:iam::942632789808:user/admin-eeoos"] }
        Action    = "sts:AssumeRole"
        Condition = { Bool = { "aws:MultiFactorAuthPresent" = "true" } }
      }]
    )
    error_message = "GitHub trust must use only AWS-supported aud plus the exact protected-environment subject and session limit."
  }

  assert {
    condition = (
      length(local.role_trust_policies.foundation) <= 2048 &&
      length(local.role_trust_policies.lab) <= 2048 &&
      length(local.role_trust_policies.image) <= 2048 &&
      length(local.role_trust_policies.dataset) <= 2048 &&
      length(local.foundation_admin_policy) <= 10240 &&
      length(local.lab_operator_managed_policies) == 5 &&
      alltrue([
        for policy in values(local.lab_operator_managed_policies) :
        length(policy.document) <= 6144
      ]) &&
      length(local.lab_host_boundary_policy) <= 6144 &&
      length(local.image_publisher_policy) <= 10240 &&
      length(local.dataset_publisher_policy) <= 10240 &&
      length(local.dns_controller_policy) <= 10240
    )
    error_message = "Role trust, inline policies, and managed policies must remain within default AWS IAM document-size quotas."
  }

  assert {
    condition = (
      toset(keys(aws_iam_policy.lab_operator)) == toset(keys(local.lab_operator_managed_policies)) &&
      toset(keys(aws_iam_role_policy_attachment.lab_operator)) == toset(keys(local.lab_operator_managed_policies)) &&
      alltrue([
        for key, policy in local.lab_operator_managed_policies :
        aws_iam_policy.lab_operator[key].name == policy.name &&
        aws_iam_policy.lab_operator[key].policy == policy.document &&
        aws_iam_role_policy_attachment.lab_operator[key].role == aws_iam_role.lab_operator.name
      ])
    )
    error_message = "Lab operator permissions must use five bounded customer-managed policies attached to the exact role."
  }


  assert {
    condition = (
      jsondecode(local.dns_controller_trust_policy).Statement[0].Principal.AWS == "arn:aws:iam::942632789808:role/airbob-lab-operator" &&
      toset(jsondecode(local.dns_controller_trust_policy).Statement[0].Action) == toset(["sts:AssumeRole", "sts:TagSession"]) &&
      one([
        for statement in jsondecode(local.dns_controller_policy).Statement : statement
        if statement.Sid == "ChangeApiARecords"
      ]).Resource == aws_route53_zone.public.arn &&
      one([
        for statement in jsondecode(local.dns_controller_policy).Statement : statement
        if statement.Sid == "ChangeApiARecords"
      ]).Condition["ForAllValues:StringEquals"]["route53:ChangeResourceRecordSetsNormalizedRecordNames"] == ["api.airbob.cloud"] &&
      one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "AssumeDnsController"
      ]).Resource == aws_iam_role.dns_controller.arn
    )
    error_message = "Only the lab operator may assume the session-tagged controller that mutates the exact public API A records."
  }

  assert {
    condition = (
      contains(one([
        for statement in jsondecode(local.lab_app_compute_policy).Statement : statement
        if statement.Sid == "ManageNamedLabAutoScaling"
      ]).Action, "autoscaling:StartInstanceRefresh") &&
      one([
        for statement in jsondecode(local.lab_app_compute_policy).Statement : statement
        if statement.Sid == "ManageNamedLabAutoScaling"
      ]).Resource == "arn:aws:autoscaling:ap-northeast-2:942632789808:autoScalingGroup:*:autoScalingGroupName/airbob-*" &&
      one([
        for statement in jsondecode(local.lab_app_compute_policy).Statement : statement
        if statement.Sid == "ManageNamedLabDashboard"
      ]).Resource == "arn:aws:cloudwatch::942632789808:dashboard/airbob-*" &&
      !contains(flatten([
        for statement in jsondecode(local.lab_app_compute_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "route53:ChangeResourceRecordSets")
    )
    error_message = "Phase 4 permissions must be limited to named ephemeral app/ALB/ASG/CloudWatch resources and must not mutate public DNS."
  }

  assert {
    condition = (
      toset(one([
        for statement in jsondecode(local.lab_data_compute_policy).Statement : statement
        if statement.Sid == "CreateRdsManagedMasterSecret"
        ]).Action) == toset([
        "secretsmanager:CreateSecret",
        "secretsmanager:TagResource",
      ]) &&
      one([
        for statement in jsondecode(local.lab_data_compute_policy).Statement : statement
        if statement.Sid == "CreateRdsManagedMasterSecret"
      ]).Resource == "arn:aws:secretsmanager:ap-northeast-2:942632789808:secret:rds!db-*" &&
      one([
        for statement in jsondecode(local.lab_data_compute_policy).Statement : statement
        if statement.Sid == "DescribeSecretsManagerKey"
      ]).Action == "kms:DescribeKey" &&
      one([
        for statement in jsondecode(local.lab_data_compute_policy).Statement : statement
        if statement.Sid == "DescribeSecretsManagerKey"
      ]).Resource == "*"
    )
    error_message = "The lab operator must be able to create only RDS-managed master secrets and describe their AWS-managed KMS key."
  }

  assert {
    condition = (
      one([
        for statement in jsondecode(local.lab_data_compute_policy).Statement : statement
        if statement.Sid == "CreateTaggedLabRds"
      ]).Resource != "*" &&
      contains(one([
        for statement in jsondecode(local.lab_data_compute_policy).Statement : statement
        if statement.Sid == "CreateTaggedLabRds"
      ]).Resource, "arn:aws:rds:ap-northeast-2:942632789808:snapshot:airbob-dataset-*") &&
      contains(one([
        for statement in jsondecode(local.lab_data_compute_policy).Statement : statement
        if statement.Sid == "CreateTaggedLabRds"
      ]).Resource, "arn:aws:rds:ap-northeast-2:942632789808:db:airbob-*")
    )
    error_message = "The lab operator must create only Airbob-named RDS resources and restore only dataset-publisher snapshots."
  }

  assert {
    condition = (
      aws_iam_policy.lab_host_boundary.name == "airbob-performance-lab-host-boundary" &&
      !contains(flatten([
        for statement in jsondecode(local.lab_host_boundary_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "iam:PutRolePolicy") &&
      one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "CreateBoundedHostRoles"
      ]).Resource == "arn:aws:iam::942632789808:role/airbob-lab-host-*" &&
      one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "CreateBoundedHostRoles"
      ]).Condition.StringEquals["iam:PermissionsBoundary"] == aws_iam_policy.lab_host_boundary.arn &&
      !contains(one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "ManageBoundedHostRoles"
      ]).Action, "iam:CreateRole") &&
      one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "PassBoundedRolesToEc2"
      ]).Resource == "arn:aws:iam::942632789808:role/airbob-lab-host-*" &&
      one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "PassBoundedRolesToEc2"
      ]).Condition.StringEquals["iam:PassedToService"] == "ec2.amazonaws.com" &&
      !contains(flatten([
        for statement in jsondecode(local.lab_compute_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "iam:UpdateAssumeRolePolicy") &&
      !contains(flatten([
        for statement in jsondecode(local.lab_compute_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "iam:PutRolePermissionsBoundary") &&
      contains(one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "MutateTaggedEc2Lab"
      ]).Action, "ec2:ModifyInstanceAttribute") &&
      one([
        for statement in jsondecode(local.lab_host_boundary_policy).Statement : statement
        if statement.Sid == "ProbeEvidenceBucketLocation"
      ]).Resource == aws_s3_bucket.managed["evidence"].arn &&
      one([
        for statement in jsondecode(local.lab_host_boundary_policy).Statement : statement
        if statement.Sid == "ProbeEvidenceBucketLocation"
      ]).Action == "s3:GetBucketLocation" &&
      contains(one([
        for statement in jsondecode(local.lab_host_boundary_policy).Statement : statement
        if statement.Sid == "ReadImmutableRuntimeInputs"
      ]).Resource, "${aws_s3_bucket.managed["evidence"].arn}/measurement-inputs/*") &&
      contains(one([
        for statement in jsondecode(local.lab_host_boundary_policy).Statement : statement
        if statement.Sid == "WriteBootstrapEvidence"
      ]).Resource, "${aws_s3_bucket.managed["evidence"].arn}/measurements/*") &&
      toset(one([
        for statement in jsondecode(local.lab_host_boundary_policy).Statement : statement
        if statement.Sid == "WriteBootstrapEvidence"
      ]).Condition.StringEquals["s3:RequestObjectTag/Retention"]) == toset(["raw", "summary"]) &&
      toset(one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "ReadNetworkReceipts"
        ]).Resource) == toset([
        "${aws_s3_bucket.managed["evidence"].arn}/network-receipts/*",
        "${aws_s3_bucket.managed["evidence"].arn}/network-clearance/*",
      ])
    )
    error_message = "Phase 2 compute permissions must force the immutable host boundary and exclude persistent-role escalation."
  }

  assert {
    condition = (
      length(local.all_ecr_repository_arns) == 10 &&
      length(local.ecr_repository_urls) == 10 &&
      length(local.ecr_repository_arns) == 10 &&
      length(local.lab_consumer_contract.ecr_repositories) == 10 &&
      alltrue([
        for repository in values(local.lab_consumer_contract.ecr_repositories) :
        toset(keys(repository)) == toset(["url", "arn"])
      ]) &&
      local.lab_consumer_contract.api_certificate_arn == aws_acm_certificate.api.arn
    )
    error_message = "The image publisher contract must remain scoped to exactly ten ECR repositories."
  }


  assert {
    condition = (
      aws_vpc.private_dns_anchor.cidr_block == "10.255.255.240/28" &&
      aws_vpc.private_dns_anchor.enable_dns_support &&
      aws_vpc.private_dns_anchor.enable_dns_hostnames &&
      aws_route53_zone.private.name == "lab.airbob.internal" &&
      !aws_route53_zone.private.force_destroy &&
      one(aws_route53_zone.private.vpc).vpc_id == aws_vpc.private_dns_anchor.id &&
      local.lab_consumer_contract.private_dns_zone_id == aws_route53_zone.private.zone_id &&
      local.lab_consumer_contract.private_dns_zone_name == "lab.airbob.internal"
    )
    error_message = "Foundation must protect the persistent private zone with a subnet-free anchor VPC."
  }

  assert {
    condition = (
      !contains(flatten([
        for statement in jsondecode(local.lab_compute_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "route53:CreateHostedZone") &&
      !contains(flatten([
        for statement in jsondecode(local.lab_compute_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "route53:DeleteHostedZone") &&
      one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "ChangePrivateServiceRecords"
      ]).Resource == aws_route53_zone.private.arn &&
      toset(one([
        for statement in jsondecode(local.lab_compute_policy).Statement : statement
        if statement.Sid == "ChangePrivateServiceRecords"
        ]).Condition["ForAllValues:StringEquals"]["route53:ChangeResourceRecordSetsNormalizedRecordNames"]) == toset([
        "connect.lab.airbob.internal",
        "elasticsearch.lab.airbob.internal",
        "kafka.lab.airbob.internal",
        "monitoring.lab.airbob.internal",
        "redis-cache.lab.airbob.internal",
        "redis-general.lab.airbob.internal",
      ])
    )
    error_message = "The lab role must manage only six A records in the exact persistent private zone."
  }

  assert {
    condition = (
      toset(jsondecode(local.image_publisher_policy).Statement[1].Action) == toset([
        "ecr:BatchCheckLayerAvailability",
        "ecr:BatchGetImage",
        "ecr:CompleteLayerUpload",
        "ecr:GetDownloadUrlForLayer",
        "ecr:InitiateLayerUpload",
        "ecr:PutImage",
        "ecr:UploadLayerPart",
      ]) &&
      toset(jsondecode(local.image_publisher_policy).Statement[2].Action) == toset(["s3:GetObject", "s3:PutObject"]) &&
      jsondecode(local.image_publisher_policy).Statement[2].Resource == "${aws_s3_bucket.managed["bundle"].arn}/service-bundles/*"
    )
    error_message = "The publisher must have only exact immutable ECR and service-bundle object actions."
  }

  assert {
    condition = (
      aws_iam_role.dataset_publisher.name == "airbob-dataset-publisher" &&
      aws_iam_role_policy.dataset_publisher.name == "airbob-dataset-publisher" &&
      aws_iam_role_policy.dataset_publisher.policy == local.dataset_publisher_policy &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteImmutableDatasetRelease"
      ]).Action == "s3:PutObject" &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteImmutableDatasetRelease"
      ]).Resource == "${aws_s3_bucket.managed["dataset"].arn}/datasets/*" &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteImmutableDatasetRelease"
      ]).Condition.StringEquals["s3:if-none-match"] == "*" &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteImmutableDatasetRelease"
      ]).Condition.StringEqualsIfExists["s3:x-amz-server-side-encryption"] == "AES256" &&
      toset(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "ReadPublishedDatasetBytes"
      ]).Action) == toset(["s3:GetObject", "s3:GetObjectVersion"]) &&
      toset(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "ReadPublishedDatasetBytes"
        ]).Resource) == toset([
        "${aws_s3_bucket.managed["dataset"].arn}/datasets/*",
        "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/releases/*",
        "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/seals/*",
      ]) &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteDatasetMultipartParts"
      ]).Condition.Bool["s3:ObjectCreationOperation"] == "false" &&
      toset(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "ManageDatasetMultipartUploads"
      ]).Action) == toset(["s3:AbortMultipartUpload", "s3:ListMultipartUploadParts"]) &&
      toset(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteElasticsearchSnapshotRepository"
      ]).Action) == toset(["s3:PutObject", "s3:PutObjectAcl"]) &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteElasticsearchSnapshotRepository"
      ]).Resource == "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/releases/rehearsal-v20/*" &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteElasticsearchSnapshotRepository"
      ]).Condition.StringEqualsIfExists["s3:x-amz-acl"] == "bucket-owner-full-control" &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteElasticsearchSnapshotRepository"
      ]).Condition.StringEqualsIfExists["s3:x-amz-server-side-encryption"] == "AES256" &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "WriteElasticsearchMultipartParts"
      ]).Condition.Bool["s3:ObjectCreationOperation"] == "false" &&
      toset(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "ManageElasticsearchSnapshotRepository"
        ]).Action) == toset([
        "s3:AbortMultipartUpload",
        "s3:DeleteObject",
        "s3:ListMultipartUploadParts",
      ]) &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "SealElasticsearchSnapshotRelease"
      ]).Resource == "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/seals/rehearsal-v20.json" &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "SealElasticsearchSnapshotRelease"
      ]).Condition.StringEquals["s3:if-none-match"] == "*" &&
      !contains(flatten([
        for statement in jsondecode(local.dataset_publisher_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "s3:DeleteObjectVersion") &&
      toset(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "InspectOwnDatasetPublisherRole"
      ]).Action) == toset(["iam:GetRole", "iam:GetRolePolicy", "iam:ListAttachedRolePolicies", "iam:ListRolePolicies"]) &&
      toset(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "OwnDatasetSnapshotLease"
      ]).Action) == toset(["dynamodb:GetItem", "dynamodb:UpdateItem"]) &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "OwnDatasetSnapshotLease"
      ]).Resource == aws_dynamodb_table.orchestration_lease.arn &&
      one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "OwnDatasetSnapshotLease"
      ]).Condition["ForAllValues:StringEquals"]["dynamodb:LeadingKeys"] == ["airbob-dataset-snapshot/rehearsal-v20"] &&
      !contains(jsondecode(local.role_trust_policies.dataset).Statement[*].Action, "sts:AssumeRoleWithWebIdentity")
    )
    error_message = "The local dataset publisher must conditionally write wrapper objects and limit native snapshot mutation to its release prefix."
  }

  assert {
    condition = (
      one([
        for statement in jsondecode(aws_s3_bucket_policy.managed["dataset"].policy).Statement : statement
        if statement.Sid == "DenyDatasetReleaseOverwrite"
      ]).Action == "s3:PutObject" &&
      one([
        for statement in jsondecode(aws_s3_bucket_policy.managed["dataset"].policy).Statement : statement
        if statement.Sid == "DenyDatasetReleaseOverwrite"
      ]).Condition.Null["s3:if-none-match"] == "true" &&
      toset(one([
        for statement in jsondecode(aws_s3_bucket_policy.managed["dataset"].policy).Statement : statement
        if statement.Sid == "DenyDatasetReleaseDeletion"
      ]).Action) == toset(["s3:DeleteObject", "s3:DeleteObjectVersion"]) &&
      one([
        for statement in jsondecode(aws_s3_bucket_policy.managed["dataset"].policy).Statement : statement
        if statement.Sid == "DenyDatasetReleaseDeletion"
      ]).Resource == "${aws_s3_bucket.managed["dataset"].arn}/datasets/*" &&
      one([
        for statement in jsondecode(aws_s3_bucket_policy.managed["dataset"].policy).Statement : statement
        if statement.Sid == "DenySnapshotSealOverwrite"
      ]).Condition.Null["s3:if-none-match"] == "true" &&
      toset(one([
        for statement in jsondecode(aws_s3_bucket_policy.managed["dataset"].policy).Statement : statement
        if statement.Sid == "DenySnapshotSealDeletion"
      ]).Action) == toset(["s3:DeleteObject", "s3:DeleteObjectVersion"]) &&
      one([
        for statement in jsondecode(aws_s3_bucket_policy.managed["dataset"].policy).Statement : statement
        if statement.Sid == "DenySnapshotSealDeletion"
      ]).Resource == "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/seals/*"
    )
    error_message = "The dataset bucket must protect wrapper bytes and immutable snapshot seals without blocking repository cleanup."
  }

  assert {
    condition = (
      !contains(flatten([
        for statement in jsondecode(local.foundation_admin_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "iam:PutRolePolicy") &&
      !contains(flatten([
        for statement in jsondecode(local.foundation_admin_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "iam:UpdateAssumeRolePolicy") &&
      !contains(flatten([
        for statement in jsondecode(local.foundation_admin_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "iam:CreateRole") &&
      one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "FoundationState"
      ]).Action == ["s3:GetObject", "s3:PutObject"] &&
      one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "FoundationStateLock"
      ]).Resource == "arn:aws:s3:::airbob-performance-lab-tfstate-942632789808/airbob/foundation/terraform.tfstate.tflock"
    )
    error_message = "The foundation role must not self-administer IAM or delete its state object."
  }

  assert {
    condition = (
      one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "BootstrapStateRead"
      ]).Resource == "arn:aws:s3:::airbob-performance-lab-tfstate-942632789808/airbob/bootstrap/terraform.tfstate" &&
      contains(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "StateBucketPostureRead"
      ]).Action, "s3:GetBucketPublicAccessBlock") &&
      toset(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ManagedBucketRead"
        ]).Action) == toset([
        "s3:GetAccelerateConfiguration",
        "s3:GetBucket*",
        "s3:GetEncryptionConfiguration",
        "s3:GetLifecycleConfiguration",
        "s3:GetObjectLockConfiguration",
        "s3:GetReplicationConfiguration",
        "s3:ListBucket",
      ]) &&
      contains(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "FoundationIdentityReadOnly"
      ]).Action, "iam:ListAttachedRolePolicies") &&
      toset(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "FoundationIdentityReadOnly"
        ]).Resource) == toset(concat(
        [
          "arn:aws:iam::942632789808:oidc-provider/token.actions.githubusercontent.com",
          "arn:aws:iam::942632789808:role/airbob-foundation-admin",
          "arn:aws:iam::942632789808:role/airbob-lab-operator",
          "arn:aws:iam::942632789808:role/airbob-image-publisher",
          "arn:aws:iam::942632789808:role/airbob-dataset-publisher",
          "arn:aws:iam::942632789808:role/airbob-dns-controller",
          "arn:aws:iam::942632789808:role/airbob-performance-lab-expiry-observer",
          "arn:aws:iam::942632789808:policy/airbob-performance-lab-host-boundary",
        ],
        [
          for policy in values(local.lab_operator_managed_policies) :
          "arn:aws:iam::942632789808:policy/${policy.name}"
        ],
      )) &&
      toset(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ExpiryObserverLambdaReadOnly"
        ]).Action) == toset([
        "lambda:GetFunction",
        "lambda:GetFunctionCodeSigningConfig",
        "lambda:GetPolicy",
        "lambda:ListVersionsByFunction",
      ]) &&
      one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ExpiryObserverLambdaReadOnly"
      ]).Resource == aws_lambda_function.expiry_observer.arn &&
      one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ExpiryObserverEventRuleReadOnly"
      ]).Resource == aws_cloudwatch_event_rule.expiry_observer.arn &&
      toset(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ExpiryObserverAlarmReadOnly"
        ]).Resource) == toset(concat(
        [for alarm in values(aws_cloudwatch_metric_alarm.expiry_observer) : alarm.arn],
        [aws_cloudwatch_metric_alarm.expiry_observer_errors.arn],
      )) &&
      one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ExpiryObserverTopicReadOnly"
      ]).Resource == aws_sns_topic.expiry_alerts.arn &&
      toset(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ExpiryObserverTopicReadOnly"
        ]).Action) == toset([
        "sns:GetSubscriptionAttributes",
        "sns:GetTopicAttributes",
        "sns:ListSubscriptionsByTopic",
        "sns:ListTagsForResource",
      ]) &&
      one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ExpiryObserverLogGroupReadOnly"
      ]).Resource == aws_cloudwatch_log_group.expiry_observer.arn &&
      one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "ExpiryObserverKeyReadOnly"
      ]).Resource == aws_kms_key.expiry_alerts.arn &&
      toset(flatten([
        for statement in jsondecode(local.foundation_admin_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
        if startswith(statement.Sid, "ExpiryObserver") && try(statement.Resource == "*", false)
      ])) == toset(["logs:DescribeLogGroups"]) &&
      contains(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "LeaseTableReadAndConfigure"
      ]).Action, "dynamodb:DescribeTimeToLive") &&
      contains(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "FoundationReadDiscovery"
      ]).Action, "ssm:DescribeParameters") &&
      !contains(one([
        for statement in jsondecode(local.foundation_admin_policy).Statement : statement
        if statement.Sid == "PublicZoneRecordsConfigure"
      ]).Condition["ForAllValues:StringEquals"]["route53:ChangeResourceRecordSetsNormalizedRecordNames"], "api.airbob.cloud")
    )
    error_message = "The foundation role must retain the exact read permissions required by backend preflight and provider refresh."
  }

  assert {
    condition = (
      one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "ReadOperatorEvidence"
      ]).Action == "s3:GetObject" &&
      toset(one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "ReadOperatorEvidence"
        ]).Resource) == toset([
        "${aws_s3_bucket.managed["evidence"].arn}/runs/*/operator.json",
        "${aws_s3_bucket.managed["evidence"].arn}/measurements/*",
      ])
      && toset(one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "OrchestrationLease"
      ]).Action) == toset(["dynamodb:GetItem", "dynamodb:UpdateItem", "dynamodb:DescribeTable"])
    )
    error_message = "The lab operator must resume from the narrow run manifest and mutate the lease without deleting its fencing history."
  }

  assert {
    condition = (
      length([
        for statement in concat(
          jsondecode(local.lab_compute_policy).Statement,
          jsondecode(local.lab_data_compute_policy).Statement,
          jsondecode(local.lab_app_compute_policy).Statement,
        ) : statement
        if try(statement.Condition.Null["aws:RequestTag/FencingToken"] == "false", false)
      ]) == 6
    )
    error_message = "All six tag-gated EC2/IAM/RDS/ASG/ELB creation statements must require the orchestration fencing token."
  }

  assert {
    condition = (
      toset(one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "OperationalState"
        ]).Resource) == toset([
        "arn:aws:s3:::airbob-performance-lab-tfstate-942632789808/airbob/dns/terraform.tfstate",
        "arn:aws:s3:::airbob-performance-lab-tfstate-942632789808/airbob/lab/terraform.tfstate",
      ]) &&
      !contains(one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "OperationalState"
      ]).Resource, "arn:aws:s3:::airbob-performance-lab-tfstate-942632789808/airbob/foundation/terraform.tfstate") &&
      one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "BootstrapStateRead"
      ]).Resource == "arn:aws:s3:::airbob-performance-lab-tfstate-942632789808/airbob/bootstrap/terraform.tfstate" &&
      contains(one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "WriteTaggedEvidence"
      ]).Action, "s3:PutObjectTagging") &&
      !contains(one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "WriteTaggedEvidence"
      ]).Action, "s3:AbortMultipartUpload") &&
      !contains(keys(one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "AbortEvidenceMultipartUpload"
      ])), "Condition") &&
      one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "ReadFoundationContracts"
        ]).Resource == [
        aws_ssm_parameter.dns_consumer_contract.arn,
        aws_ssm_parameter.lab_consumer_contract.arn,
      ] &&
      one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "ReadApiOriginRecords"
      ]).Resource == aws_route53_zone.public.arn &&
      one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "ReadRoute53Changes"
      ]).Resource == "arn:aws:route53:::change/*" &&
      toset(one([
        for statement in jsondecode(local.lab_operator_policy).Statement : statement
        if statement.Sid == "ReadLabLoadBalancer"
        ]).Action) == toset([
        "elasticloadbalancing:DescribeLoadBalancerAttributes",
        "elasticloadbalancing:DescribeLoadBalancers",
        "elasticloadbalancing:DescribeTags",
      ]) &&
      !contains(flatten([
        for statement in jsondecode(local.lab_operator_policy).Statement :
        try(tolist(statement.Action), [statement.Action])
      ]), "route53:ChangeResourceRecordSets")
    )
    error_message = "Lab permissions must support tagged evidence and read-only DNS/ALB refresh without direct Route 53 mutation."
  }

  assert {
    condition = (
      aws_lambda_function.expiry_observer.runtime == "python3.14" &&
      one(aws_lambda_function.expiry_observer.architectures) == "arm64" &&
      aws_lambda_function.expiry_observer.reserved_concurrent_executions == null
    )
    error_message = "The observer Lambda must use the pinned runtime and architecture without account-level reserved concurrency."
  }

  assert {
    condition = (
      one(aws_lambda_function.expiry_observer.environment).variables.CLEANUP_ENABLED == "false" &&
      one(aws_lambda_function.expiry_observer.environment).variables.METRIC_NAMESPACE == "Airbob/PerformanceLab"
    )
    error_message = "The observer Lambda must reject cleanup and use the fixed metric namespace."
  }

  assert {
    condition = (
      aws_cloudwatch_event_rule.expiry_observer.schedule_expression == "rate(15 minutes)" &&
      aws_cloudwatch_event_rule.expiry_observer.state == "ENABLED" &&
      toset(keys(aws_cloudwatch_metric_alarm.expiry_observer)) == toset([
        "action-required",
        "heartbeat-missing",
      ]) &&
      alltrue([for alarm in aws_cloudwatch_metric_alarm.expiry_observer : alarm.actions_enabled]) &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.actions_enabled &&
      length(aws_sns_topic_subscription.expiry_alert_email) == 1 &&
      aws_sns_topic_subscription.expiry_alert_email[0].endpoint == "alerts@example.com" &&
      aws_sns_topic.expiry_alerts.kms_master_key_id == aws_kms_key.expiry_alerts.arn &&
      aws_kms_key.expiry_alerts.enable_key_rotation &&
      aws_kms_key.expiry_alerts.deletion_window_in_days == 30 &&
      one([
        for statement in jsondecode(aws_kms_key.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Principal.Service == "cloudwatch.amazonaws.com" &&
      toset(one([
        for statement in jsondecode(aws_kms_key.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Action) == toset(["kms:Decrypt", "kms:GenerateDataKey*"]) &&
      one([
        for statement in jsondecode(aws_kms_key.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Condition.StringEquals["aws:SourceAccount"] == "942632789808" &&
      one([
        for statement in jsondecode(aws_kms_key.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Condition.ArnLike["aws:SourceArn"] == "arn:aws:cloudwatch:ap-northeast-2:942632789808:alarm:airbob-performance-lab-expiry-*" &&
      output.expiry_observer.subscription_confirmed &&
      output.expiry_observer.cleanup_enabled == false
    )
    error_message = "The enabled observer must be scheduled and connected to its bounded alert path."
  }

  assert {
    condition = (
      aws_cloudwatch_event_target.expiry_observer.rule == aws_cloudwatch_event_rule.expiry_observer.name &&
      aws_cloudwatch_event_target.expiry_observer.arn == aws_lambda_function.expiry_observer.arn &&
      aws_lambda_permission.expiry_observer_schedule.action == "lambda:InvokeFunction" &&
      aws_lambda_permission.expiry_observer_schedule.principal == "events.amazonaws.com" &&
      aws_lambda_permission.expiry_observer_schedule.source_arn == aws_cloudwatch_event_rule.expiry_observer.arn &&
      alltrue([
        for alarm in aws_cloudwatch_metric_alarm.expiry_observer :
        toset(alarm.alarm_actions) == toset([aws_sns_topic.expiry_alerts.arn])
      ]) &&
      toset(aws_cloudwatch_metric_alarm.expiry_observer_errors.alarm_actions) == toset([aws_sns_topic.expiry_alerts.arn]) &&
      one([
        for statement in jsondecode(aws_sns_topic_policy.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Principal.Service == "cloudwatch.amazonaws.com" &&
      one([
        for statement in jsondecode(aws_sns_topic_policy.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Action == "sns:Publish" &&
      one([
        for statement in jsondecode(aws_sns_topic_policy.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Resource == aws_sns_topic.expiry_alerts.arn &&
      one([
        for statement in jsondecode(aws_sns_topic_policy.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Condition.StringEquals["aws:SourceAccount"] == "942632789808" &&
      one([
        for statement in jsondecode(aws_sns_topic_policy.expiry_alerts.policy).Statement : statement
        if statement.Sid == "AllowCloudWatchExpiryAlarms"
      ]).Condition.ArnLike["aws:SourceArn"] == "arn:aws:cloudwatch:ap-northeast-2:942632789808:alarm:airbob-performance-lab-expiry-*"
    )
    error_message = "EventBridge, Lambda, alarms, and the bounded SNS publisher policy must remain connected exactly."
  }

  assert {
    condition = (
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].namespace == "Airbob/PerformanceLab" &&
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].metric_name == "ActionRequiredCount" &&
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].comparison_operator == "GreaterThanOrEqualToThreshold" &&
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].evaluation_periods == 1 &&
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].datapoints_to_alarm == 1 &&
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].period == 900 &&
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].statistic == "Maximum" &&
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].threshold == 1 &&
      aws_cloudwatch_metric_alarm.expiry_observer["action-required"].treat_missing_data == "notBreaching" &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].namespace == "Airbob/PerformanceLab" &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].metric_name == "ObserverHeartbeat" &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].comparison_operator == "LessThanThreshold" &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].evaluation_periods == 2 &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].datapoints_to_alarm == 2 &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].period == 900 &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].statistic == "Minimum" &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].threshold == 1 &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].treat_missing_data == "breaching" &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.namespace == "AWS/Lambda" &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.metric_name == "Errors" &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.comparison_operator == "GreaterThanOrEqualToThreshold" &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.evaluation_periods == 1 &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.datapoints_to_alarm == 1 &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.period == 300 &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.statistic == "Sum" &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.threshold == 1 &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.treat_missing_data == "notBreaching" &&
      aws_cloudwatch_metric_alarm.expiry_observer_errors.dimensions.FunctionName == aws_lambda_function.expiry_observer.function_name
    )
    error_message = "Observer metrics and alarm thresholds must remain an exact executable contract."
  }

  assert {
    condition = (
      toset(flatten([
        for statement in jsondecode(aws_iam_role_policy.expiry_observer.policy).Statement :
        try(tolist(statement.Action), [statement.Action])
        ])) == toset([
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "tag:GetResources",
        "cloudwatch:PutMetricData",
      ]) &&
      one([
        for statement in jsondecode(aws_iam_role_policy.expiry_observer.policy).Statement : statement
        if statement.Sid == "WriteExpiryMetrics"
      ]).Condition.StringEquals["cloudwatch:namespace"] == "Airbob/PerformanceLab"
    )
    error_message = "The observer role must contain only log, tag-read, and namespace-scoped metric permissions."
  }
}

run "expiry_observer_disabled_by_default_contract" {
  command = plan

  variables {
    expiry_observer_enabled             = false
    expiry_alert_email                  = null
    expiry_alert_subscription_confirmed = false
  }

  assert {
    condition = (
      aws_cloudwatch_event_rule.expiry_observer.state == "DISABLED" &&
      toset(keys(aws_cloudwatch_metric_alarm.expiry_observer)) == toset([
        "action-required",
        "heartbeat-missing",
      ]) &&
      alltrue([for alarm in aws_cloudwatch_metric_alarm.expiry_observer : !alarm.actions_enabled]) &&
      !aws_cloudwatch_metric_alarm.expiry_observer_errors.actions_enabled &&
      aws_cloudwatch_metric_alarm.expiry_observer["heartbeat-missing"].treat_missing_data == "notBreaching" &&
      length(aws_sns_topic_subscription.expiry_alert_email) == 0 &&
      !output.expiry_observer.cleanup_enabled
    )
    error_message = "The observer schedule and alarm actions must stay inactive while stable alarm resources remain declared."
  }
}

run "reject_enabled_observer_without_email" {
  command = plan

  variables {
    expiry_observer_enabled             = true
    expiry_alert_email                  = null
    expiry_alert_subscription_confirmed = true
  }

  expect_failures = [aws_cloudwatch_event_rule.expiry_observer]
}

run "reject_enabled_observer_without_subscription_confirmation" {
  command = plan

  variables {
    expiry_observer_enabled             = true
    expiry_alert_email                  = "alerts@example.com"
    expiry_alert_subscription_confirmed = false
  }

  expect_failures = [aws_cloudwatch_event_rule.expiry_observer]
}

run "reject_enabled_observer_with_pending_subscription" {
  command = plan

  variables {
    expiry_observer_enabled             = true
    expiry_alert_email                  = "alerts@example.com"
    expiry_alert_subscription_confirmed = true
  }

  override_resource {
    target          = aws_sns_topic_subscription.expiry_alert_email[0]
    override_during = plan
    values = {
      pending_confirmation = true
    }
  }

  expect_failures = [aws_cloudwatch_event_rule.expiry_observer]
}

run "reject_subscription_confirmation_without_email" {
  command = plan

  variables {
    expiry_observer_enabled             = false
    expiry_alert_email                  = null
    expiry_alert_subscription_confirmed = true
  }

  expect_failures = [aws_cloudwatch_event_rule.expiry_observer]
}

run "reject_noncanonical_expiry_alert_email" {
  command = plan

  variables {
    expiry_observer_enabled = false
    expiry_alert_email      = " alerts@example.com"
  }

  expect_failures = [var.expiry_alert_email]
}

run "delegated_certificate_validation" {
  command = plan

  variables {
    dns_delegation_confirmed = true
  }

  assert {
    condition     = length(aws_acm_certificate_validation.api) == 1
    error_message = "The second foundation pass must enable ACM validation only after DNS delegation is confirmed."
  }
}

run "reject_wrong_account" {
  command = plan

  variables {
    account_id = "111111111111"
  }

  expect_failures = [var.account_id]
}

run "reject_wrong_region" {
  command = plan

  variables {
    aws_region = "us-east-1"
  }

  expect_failures = [var.aws_region]
}

run "reject_unreviewed_inventory" {
  command = plan

  variables {
    dns_inventory_reviewed = false
  }

  expect_failures = [var.dns_inventory_reviewed]
}

run "reject_unreviewed_dnssec" {
  command = plan

  variables {
    dnssec_ds_reviewed = false
  }

  expect_failures = [var.dnssec_ds_reviewed]
}

run "reject_reserved_api_record" {
  command = plan

  variables {
    static_dns_records = {
      api = {
        name    = "api.airbob.cloud"
        type    = "A"
        ttl     = 60
        records = ["140.245.76.140"]
      }
    }
  }

  expect_failures = [var.static_dns_records]
}

run "reject_acm_validation_inventory_record" {
  command = plan

  variables {
    static_dns_records = {
      acm = {
        name    = "_validation.api.airbob.cloud"
        type    = "CNAME"
        ttl     = 60
        records = ["_value.acm-validations.aws."]
      }
    }
  }

  expect_failures = [var.static_dns_records]
}

run "reject_duplicate_static_record_set" {
  command = plan

  variables {
    static_dns_records = {
      apex_a = {
        name    = "airbob.cloud"
        type    = "A"
        ttl     = 300
        records = ["76.76.21.21"]
      }
      duplicate_apex_a = {
        name    = "airbob.cloud"
        type    = "a"
        ttl     = 60
        records = ["76.76.21.21"]
      }
    }
  }

  expect_failures = [var.static_dns_records]
}

run "reject_cname_coexistence" {
  command = plan

  variables {
    static_dns_records = {
      www_cname = {
        name    = "www.airbob.cloud"
        type    = "CNAME"
        ttl     = 300
        records = ["cname.vercel-dns.com."]
      }
      www_txt = {
        name    = "www.airbob.cloud"
        type    = "TXT"
        ttl     = 300
        records = ["verification=value"]
      }
    }
  }

  expect_failures = [var.static_dns_records]
}

run "reject_managed_application_bucket" {
  command = plan

  variables {
    existing_application_bucket_name = "airbob-performance-lab-dataset-942632789808"
  }

  expect_failures = [var.existing_application_bucket_name]
}

run "reject_unreviewed_oidc_subjects" {
  command = plan

  variables {
    github_oidc_subjects_reviewed = false
  }

  expect_failures = [var.github_oidc_subjects_reviewed]
}

run "immutable_protected_environment_subjects" {
  command = plan

  variables {
    github_foundation_subject = "repo:eeoos@119295425/airbob@1056501820:environment:aws-foundation"
    github_lab_subject        = "repo:eeoos@119295425/airbob@1056501820:environment:aws-performance-lab"
    github_image_subject      = "repo:eeoos@119295425/airbob@1056501820:environment:aws-image-publisher"
  }

  assert {
    condition = (
      local.github_role_trust.foundation["token.actions.githubusercontent.com:sub"] == "repo:eeoos@119295425/airbob@1056501820:environment:aws-foundation" &&
      local.github_role_trust.lab["token.actions.githubusercontent.com:sub"] == "repo:eeoos@119295425/airbob@1056501820:environment:aws-performance-lab" &&
      local.github_role_trust.image["token.actions.githubusercontent.com:sub"] == "repo:eeoos@119295425/airbob@1056501820:environment:aws-image-publisher"
    )
    error_message = "Immutable subjects must bind all three protected GitHub environments."
  }
}

run "reject_main_branch_image_subject_without_environment" {
  command = plan

  variables {
    github_image_subject = "repo:eeoos/airbob:ref:refs/heads/main"
  }

  expect_failures = [var.github_image_subject]
}

run "reject_guessed_oidc_subject" {
  command = plan

  variables {
    github_foundation_subject = "repo:eeoos/*:environment:aws-foundation"
  }

  expect_failures = [var.github_foundation_subject]
}

run "reject_mixed_oidc_subject_modes" {
  command = plan

  variables {
    github_foundation_subject = "repo:eeoos@119295425/airbob@1056501820:environment:aws-foundation"
  }

  expect_failures = [check.github_oidc_subject_mode_consistency]
}

run "mfa_disabled_omits_condition" {
  command = plan

  variables {
    local_principal_requires_mfa = false
  }

  assert {
    condition = (
      !contains(keys(one([
        for statement in jsondecode(local.role_trust_policies.foundation).Statement : statement
        if statement.Sid == "ApprovedLocalPrincipals"
      ])), "Condition") &&
      !contains(keys(one([
        for statement in jsondecode(local.role_trust_policies.lab).Statement : statement
        if statement.Sid == "ApprovedLocalPrincipals"
      ])), "Condition") &&
      one(jsondecode(local.role_trust_policies.dataset).Statement).Condition == {
        Bool = { "aws:MultiFactorAuthPresent" = "true" }
      }
    )
    error_message = "Disabling shared MFA must affect only foundation/lab; the dataset publisher always requires MFA."
  }
}

run "preserve_enabled_application_ecr_scanning" {
  command = plan

  variables {
    application_ecr_scan_on_push = true
  }

  assert {
    condition     = one(aws_ecr_repository.application.image_scanning_configuration).scan_on_push
    error_message = "The imported application repository must accept an explicitly observed enabled scanning state."
  }
}

run "reject_mixed_case_reserved_api_record" {
  command = plan

  variables {
    static_dns_records = {
      api = {
        name    = "API.airbob.cloud"
        type    = "A"
        ttl     = 60
        records = ["140.245.76.140"]
      }
    }
  }

  expect_failures = [var.static_dns_records]
}

run "reject_blank_dns_record_value" {
  command = plan

  variables {
    static_dns_records = {
      blank = {
        name    = "www.airbob.cloud"
        type    = "TXT"
        ttl     = 60
        records = [" "]
      }
    }
  }

  expect_failures = [var.static_dns_records]
}

run "reject_apex_cname" {
  command = plan

  variables {
    static_dns_records = {
      apex = {
        name    = "airbob.cloud"
        type    = "CNAME"
        ttl     = 60
        records = ["origin.example.com."]
      }
    }
  }

  expect_failures = [var.static_dns_records]
}

run "reject_out_of_zone_record" {
  command = plan

  variables {
    static_dns_records = {
      outside = {
        name    = "airbob.cloud.example.com"
        type    = "A"
        ttl     = 60
        records = ["192.0.2.1"]
      }
    }
  }

  expect_failures = [var.static_dns_records]
}

run "reject_foreign_foundation_local_principal" {
  command = plan

  variables {
    foundation_local_principal_arns = ["arn:aws:iam::111111111111:user/foreign"]
  }

  expect_failures = [var.foundation_local_principal_arns]
}

run "reject_foreign_lab_local_principal" {
  command = plan

  variables {
    lab_local_principal_arns = ["arn:aws:iam::111111111111:user/foreign"]
  }

  expect_failures = [var.lab_local_principal_arns]
}

run "reject_foreign_dataset_publisher_local_principal" {
  command = plan

  variables {
    dataset_publisher_local_principal_arns = ["arn:aws:iam::111111111111:user/foreign"]
  }

  expect_failures = [var.dataset_publisher_local_principal_arns]
}

run "reject_alternate_dataset_publisher_local_principal" {
  command = plan

  variables {
    dataset_publisher_local_principal_arns = ["arn:aws:iam::942632789808:user/another-admin"]
  }

  expect_failures = [var.dataset_publisher_local_principal_arns]
}

run "reject_multiple_dataset_publisher_local_principals" {
  command = plan

  variables {
    dataset_publisher_local_principal_arns = [
      "arn:aws:iam::942632789808:user/admin-eeoos",
      "arn:aws:iam::942632789808:user/another-admin",
    ]
  }

  expect_failures = [var.dataset_publisher_local_principal_arns]
}

run "reject_shared_foundation_and_lab_local_principal" {
  command = plan

  variables {
    foundation_local_principal_arns = ["arn:aws:iam::942632789808:user/shared"]
    lab_local_principal_arns        = ["arn:aws:iam::942632789808:user/shared"]
  }

  expect_failures = [check.local_principal_role_separation]
}

run "reject_shared_lab_and_dataset_publisher_local_principal" {
  command = plan

  variables {
    lab_local_principal_arns = ["arn:aws:iam::942632789808:user/admin-eeoos"]
  }

  expect_failures = [check.local_principal_role_separation]
}

run "revoke_real_snapshot_writer_prefix" {
  command = plan

  variables {
    dataset_snapshot_writer_release = null
  }

  assert {
    condition = alltrue([
      for statement in jsondecode(local.dataset_publisher_policy).Statement :
      statement.Resource == "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/releases/__disabled__/*"
      if contains([
        "WriteElasticsearchSnapshotRepository",
        "WriteElasticsearchMultipartParts",
        "ManageElasticsearchSnapshotRepository",
      ], statement.Sid)
    ])
    error_message = "Revoking the snapshot writer must leave no real Elasticsearch release prefix writable."
  }

  assert {
    condition = one([
      for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
      if statement.Sid == "OwnDatasetSnapshotLease"
    ]).Condition["ForAllValues:StringEquals"]["dynamodb:LeadingKeys"] == ["airbob-dataset-snapshot/__disabled__"]
    error_message = "Revoking the snapshot writer must also revoke the real release lease key."
  }

  assert {
    condition = one([
      for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
      if statement.Sid == "SealElasticsearchSnapshotRelease"
    ]).Resource == "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/seals/__disabled__.json"
    error_message = "Revoking the snapshot writer must also revoke the real immutable seal key."
  }

  assert {
    condition = (
      toset(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "ReadPublishedDatasetBytes"
      ]).Action) == toset(["s3:GetObject", "s3:GetObjectVersion"]) &&
      contains(one([
        for statement in jsondecode(local.dataset_publisher_policy).Statement : statement
        if statement.Sid == "ReadPublishedDatasetBytes"
      ]).Resource, "${aws_s3_bucket.managed["dataset"].arn}/elasticsearch/seals/*")
    )
    error_message = "Revoking the snapshot writer must preserve read-only access to every immutable seal."
  }
}

run "reject_unsafe_snapshot_writer_release" {
  command = plan

  variables {
    dataset_snapshot_writer_release = "Unsafe/Release"
  }

  expect_failures = [var.dataset_snapshot_writer_release]
}
