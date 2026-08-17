locals {
  expiry_observer_name      = "airbob-performance-lab-expiry-observer"
  expiry_metric_namespace   = "Airbob/PerformanceLab"
  expiry_alarm_name_prefix  = "airbob-performance-lab-expiry"
  expiry_observer_log_group = "/aws/lambda/${local.expiry_observer_name}"
  expiry_custom_alarms = {
    action-required = {
      metric_name         = "ActionRequiredCount"
      comparison_operator = "GreaterThanOrEqualToThreshold"
      evaluation_periods  = 1
      datapoints_to_alarm = 1
      statistic           = "Maximum"
      threshold           = 1
      treat_missing_data  = "notBreaching"
    }
    heartbeat-missing = {
      metric_name         = "ObserverHeartbeat"
      comparison_operator = "LessThanThreshold"
      evaluation_periods  = 2
      datapoints_to_alarm = 2
      statistic           = "Minimum"
      threshold           = 1
      treat_missing_data  = "breaching"
    }
  }
  expiry_alert_delivery_ready = (
    var.expiry_observer_enabled &&
    var.expiry_alert_subscription_confirmed &&
    length(aws_sns_topic_subscription.expiry_alert_email) == 1 &&
    try(!aws_sns_topic_subscription.expiry_alert_email[0].pending_confirmation, false)
  )
}

data "archive_file" "expiry_observer" {
  type        = "zip"
  source_file = "${path.module}/lambda/expiry_observer.py"
  output_path = "${path.module}/.terraform/expiry-observer.zip"
}

resource "aws_cloudwatch_log_group" "expiry_observer" {
  name              = local.expiry_observer_log_group
  retention_in_days = 30

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role" "expiry_observer" {
  name = local.expiry_observer_name
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = {
        Service = "lambda.amazonaws.com"
      }
      Action = "sts:AssumeRole"
    }]
  })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_iam_role_policy" "expiry_observer" {
  name = "${local.expiry_observer_name}-read-only"
  role = aws_iam_role.expiry_observer.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "WriteObserverLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.expiry_observer.arn}:*"
      },
      {
        Sid      = "ReadEphemeralResourceTags"
        Effect   = "Allow"
        Action   = "tag:GetResources"
        Resource = "*"
      },
      {
        Sid      = "WriteExpiryMetrics"
        Effect   = "Allow"
        Action   = "cloudwatch:PutMetricData"
        Resource = "*"
        Condition = {
          StringEquals = {
            "cloudwatch:namespace" = local.expiry_metric_namespace
          }
        }
      },
    ]
  })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_lambda_function" "expiry_observer" {
  function_name = local.expiry_observer_name
  role          = aws_iam_role.expiry_observer.arn
  handler       = "expiry_observer.lambda_handler"
  runtime       = "python3.14"
  architectures = ["arm64"]

  filename         = data.archive_file.expiry_observer.output_path
  source_code_hash = data.archive_file.expiry_observer.output_base64sha256

  memory_size = 128
  timeout     = 60

  environment {
    variables = {
      CLEANUP_ENABLED  = "false"
      METRIC_NAMESPACE = local.expiry_metric_namespace
    }
  }

  depends_on = [
    aws_cloudwatch_log_group.expiry_observer,
    aws_iam_role_policy.expiry_observer,
  ]

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_cloudwatch_event_rule" "expiry_observer" {
  name                = local.expiry_observer_name
  description         = "Read-only observer for expired Airbob performance-lab resources."
  schedule_expression = "rate(15 minutes)"
  state               = local.expiry_alert_delivery_ready ? "ENABLED" : "DISABLED"

  lifecycle {
    prevent_destroy = true

    precondition {
      condition     = !var.expiry_alert_subscription_confirmed || var.expiry_alert_email != null
      error_message = "Subscription confirmation cannot be acknowledged without a configured expiry alert email."
    }

    precondition {
      condition     = !var.expiry_observer_enabled || var.expiry_alert_subscription_confirmed
      error_message = "Confirm and acknowledge the reviewed expiry alert email before enabling the observer."
    }

    precondition {
      condition = !var.expiry_observer_enabled || (
        length(aws_sns_topic_subscription.expiry_alert_email) == 1 &&
        !aws_sns_topic_subscription.expiry_alert_email[0].pending_confirmation
      )
      error_message = "The configured SNS email subscription must be confirmed in AWS before enabling the observer."
    }
  }
}

resource "aws_lambda_permission" "expiry_observer_schedule" {
  statement_id  = "AllowEventBridgeExpiryObserver"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.expiry_observer.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.expiry_observer.arn

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_cloudwatch_event_target" "expiry_observer" {
  rule      = aws_cloudwatch_event_rule.expiry_observer.name
  target_id = "expiry-observer"
  arn       = aws_lambda_function.expiry_observer.arn

  depends_on = [aws_lambda_permission.expiry_observer_schedule]

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_kms_key" "expiry_alerts" {
  description             = "Encrypts Airbob performance-lab expiry alert notifications."
  deletion_window_in_days = 30
  enable_key_rotation     = true
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "EnableAccountAdministration"
        Effect = "Allow"
        Principal = {
          AWS = "arn:aws:iam::${var.account_id}:root"
        }
        Action   = "kms:*"
        Resource = "*"
      },
      {
        Sid    = "AllowCloudWatchExpiryAlarms"
        Effect = "Allow"
        Principal = {
          Service = "cloudwatch.amazonaws.com"
        }
        Action   = ["kms:Decrypt", "kms:GenerateDataKey*"]
        Resource = "*"
        Condition = {
          StringEquals = {
            "aws:SourceAccount" = var.account_id
          }
          ArnLike = {
            "aws:SourceArn" = "arn:aws:cloudwatch:${var.aws_region}:${var.account_id}:alarm:${local.expiry_alarm_name_prefix}-*"
          }
        }
      },
    ]
  })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_sns_topic" "expiry_alerts" {
  name              = "${local.expiry_alarm_name_prefix}-alerts"
  kms_master_key_id = aws_kms_key.expiry_alerts.arn

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_sns_topic_policy" "expiry_alerts" {
  arn = aws_sns_topic.expiry_alerts.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "AllowCloudWatchExpiryAlarms"
      Effect = "Allow"
      Principal = {
        Service = "cloudwatch.amazonaws.com"
      }
      Action   = "sns:Publish"
      Resource = aws_sns_topic.expiry_alerts.arn
      Condition = {
        StringEquals = {
          "aws:SourceAccount" = var.account_id
        }
        ArnLike = {
          "aws:SourceArn" = "arn:aws:cloudwatch:${var.aws_region}:${var.account_id}:alarm:${local.expiry_alarm_name_prefix}-*"
        }
      }
    }]
  })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_sns_topic_subscription" "expiry_alert_email" {
  count = var.expiry_alert_email == null ? 0 : 1

  topic_arn = aws_sns_topic.expiry_alerts.arn
  protocol  = "email"
  endpoint  = var.expiry_alert_email
}

resource "aws_cloudwatch_metric_alarm" "expiry_observer" {
  for_each = local.expiry_custom_alarms

  alarm_name          = "${local.expiry_alarm_name_prefix}-${each.key}"
  alarm_description   = "Expiry observer requires attention; automatic cleanup is disabled."
  namespace           = local.expiry_metric_namespace
  metric_name         = each.value.metric_name
  comparison_operator = each.value.comparison_operator
  evaluation_periods  = each.value.evaluation_periods
  datapoints_to_alarm = each.value.datapoints_to_alarm
  period              = 900
  statistic           = each.value.statistic
  threshold           = each.value.threshold
  treat_missing_data  = each.key == "heartbeat-missing" && !var.expiry_observer_enabled ? "notBreaching" : each.value.treat_missing_data
  alarm_actions       = [aws_sns_topic.expiry_alerts.arn]
  actions_enabled     = local.expiry_alert_delivery_ready

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_cloudwatch_metric_alarm" "expiry_observer_errors" {
  alarm_name          = "${local.expiry_alarm_name_prefix}-lambda-errors"
  alarm_description   = "The read-only expiry observer Lambda returned an error."
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  datapoints_to_alarm = 1
  period              = 300
  statistic           = "Sum"
  threshold           = 1
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.expiry_alerts.arn]
  actions_enabled     = local.expiry_alert_delivery_ready

  dimensions = {
    FunctionName = aws_lambda_function.expiry_observer.function_name
  }

  lifecycle {
    prevent_destroy = true
  }
}
