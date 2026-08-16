locals {
  dependency_instance_metrics = flatten([
    for service, instance_id in module.service_hosts.instance_ids : [
      ["AWS/EC2", "CPUUtilization", "InstanceId", instance_id, { label = "${service} CPU", stat = "Average" }],
      ["AWS/EC2", "CPUCreditBalance", "InstanceId", instance_id, { label = "${service} credits", stat = "Minimum", yAxis = "right" }],
      ["AWS/EC2", "CPUSurplusCreditBalance", "InstanceId", instance_id, { label = "${service} surplus", stat = "Maximum", yAxis = "right" }],
      ["AWS/EC2", "CPUSurplusCreditsCharged", "InstanceId", instance_id, { label = "${service} charged", stat = "Sum", yAxis = "right" }],
    ]
  ])

  load_generator_metrics = flatten([
    for generator in module.load_generator : [
      ["AWS/EC2", "CPUUtilization", "InstanceId", generator.instance_id, { label = "loadgen CPU", stat = "Average" }],
      ["AWS/EC2", "NetworkOut", "InstanceId", generator.instance_id, { label = "loadgen network out", stat = "Sum", yAxis = "right" }],
    ]
  ])
}

resource "aws_cloudwatch_dashboard" "lab" {
  count = local.services_enabled ? 1 : 0

  dashboard_name = "airbob-${var.run_id}"
  dashboard_body = jsonencode({
    start          = "-PT3H"
    periodOverride = "inherit"
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6
        properties = {
          title = "ALB response and traffic", region = var.aws_region, period = 60, view = "timeSeries"
          metrics = [
            ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", module.alb[0].arn_suffix, { stat = "p95", label = "target response p95" }],
            ["AWS/ApplicationELB", "RequestCountPerTarget", "TargetGroup", module.alb[0].target_group_arn_suffix, "LoadBalancer", module.alb[0].arn_suffix, { stat = "Sum" }],
            ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "TargetGroup", module.alb[0].target_group_arn_suffix, "LoadBalancer", module.alb[0].arn_suffix, { stat = "Sum" }],
            ["AWS/ApplicationELB", "HealthyHostCount", "TargetGroup", module.alb[0].target_group_arn_suffix, "LoadBalancer", module.alb[0].arn_suffix, { stat = "Minimum", yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6
        properties = {
          title = "ASG capacity", region = var.aws_region, period = 60, view = "timeSeries"
          metrics = [
            ["AWS/AutoScaling", "GroupDesiredCapacity", "AutoScalingGroupName", module.app_asg[0].name, { stat = "Average" }],
            ["AWS/AutoScaling", "GroupInServiceInstances", "AutoScalingGroupName", module.app_asg[0].name, { stat = "Average" }],
            ["AWS/AutoScaling", "GroupPendingInstances", "AutoScalingGroupName", module.app_asg[0].name, { stat = "Average" }],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6
        properties = {
          title = "RDS", region = var.aws_region, period = 60, view = "timeSeries"
          metrics = [
            ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", module.rds[0].id, { stat = "Average" }],
            ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", module.rds[0].id, { stat = "Average" }],
            ["AWS/RDS", "FreeableMemory", "DBInstanceIdentifier", module.rds[0].id, { stat = "Minimum", yAxis = "right" }],
            ["AWS/RDS", "CPUCreditBalance", "DBInstanceIdentifier", module.rds[0].id, { stat = "Minimum", yAxis = "right" }],
            ["AWS/RDS", "CPUSurplusCreditBalance", "DBInstanceIdentifier", module.rds[0].id, { stat = "Maximum", yAxis = "right" }],
            ["AWS/RDS", "CPUSurplusCreditsCharged", "DBInstanceIdentifier", module.rds[0].id, { stat = "Sum", yAxis = "right" }],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6
        properties = {
          title   = "T3 dependency CPU and credits", region = var.aws_region, period = 60, view = "timeSeries"
          metrics = local.dependency_instance_metrics
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6
        properties = {
          title   = "Load generator", region = var.aws_region, period = 60, view = "timeSeries"
          metrics = local.load_generator_metrics
        }
      },
    ]
  })
}
