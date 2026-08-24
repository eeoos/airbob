locals {
  instance_tags = merge(var.tags, {
    Name            = "${var.name_prefix}-app"
    Service         = "app"
    Monitoring      = "node-exporter"
    RuntimeRevision = var.runtime_revision
  })
}

resource "aws_launch_template" "app" {
  name_prefix            = "${var.name_prefix}-app-"
  image_id               = var.ami_id
  instance_type          = var.instance_type
  update_default_version = true
  user_data              = base64encode(var.user_data)
  ebs_optimized          = true

  iam_instance_profile {
    name = var.instance_profile_name
  }

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  monitoring {
    enabled = true
  }

  network_interfaces {
    associate_public_ip_address = false
    delete_on_termination       = true
    device_index                = 0
    security_groups             = var.security_group_ids
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      delete_on_termination = true
      encrypted             = true
      volume_size           = 30
      volume_type           = "gp3"
    }
  }

  tag_specifications {
    resource_type = "instance"
    tags          = local.instance_tags
  }

  tag_specifications {
    resource_type = "volume"
    tags          = merge(var.tags, { Name = "${var.name_prefix}-app-root", Service = "app" })
  }

  tags = merge(var.tags, { Service = "app" })
}

resource "aws_autoscaling_group" "app" {
  name                      = "${var.name_prefix}-app"
  min_size                  = var.min_size
  desired_capacity          = var.desired_capacity
  max_size                  = var.max_size
  vpc_zone_identifier       = var.subnet_ids
  target_group_arns         = var.target_group_arns
  health_check_type         = "ELB"
  health_check_grace_period = 900
  default_instance_warmup   = 180
  capacity_rebalance        = false
  protect_from_scale_in     = false
  force_delete              = true
  wait_for_capacity_timeout = "0"

  enabled_metrics = [
    "GroupDesiredCapacity",
    "GroupInServiceCapacity",
    "GroupInServiceInstances",
    "GroupMaxSize",
    "GroupMinSize",
    "GroupPendingInstances",
    "GroupTerminatingInstances",
    "GroupTotalCapacity",
    "GroupTotalInstances",
  ]
  metrics_granularity = "1Minute"

  launch_template {
    id      = aws_launch_template.app.id
    version = tostring(aws_launch_template.app.latest_version)
  }

  instance_refresh {
    strategy = "Rolling"

    preferences {
      auto_rollback                = true
      instance_warmup              = 180
      min_healthy_percentage       = var.mode == "performance" ? 0 : 100
      max_healthy_percentage       = var.mode == "performance" ? 100 : 200
      checkpoint_percentages       = var.mode == "scaling" ? [50, 100] : null
      checkpoint_delay             = var.mode == "scaling" ? 60 : null
      skip_matching                = true
      scale_in_protected_instances = "Refresh"
      standby_instances            = "Terminate"

      alarm_specification {
        alarms = var.refresh_alarm_names
      }
    }
  }

  dynamic "tag" {
    for_each = local.instance_tags
    content {
      key                 = tag.key
      value               = tag.value
      propagate_at_launch = true
    }
  }

  lifecycle {
    precondition {
      condition = (
        (var.mode == "performance" && length(var.subnet_ids) == 1) ||
        (contains(["distributed-lock", "scaling"], var.mode) && length(var.subnet_ids) == 2)
      )
      error_message = "Performance mode requires one subnet; distributed-lock and scaling require both private-AZ subnets."
    }

    precondition {
      condition = (
        (!var.app_enabled && var.min_size == 0 && var.desired_capacity == 0 && var.max_size == 0) ||
        (var.app_enabled && var.mode == "performance" && var.min_size == 1 && var.desired_capacity == 1 && var.max_size == 1) ||
        (var.app_enabled && var.mode == "distributed-lock" && var.min_size == 2 && var.desired_capacity == 2 && var.max_size == 2) ||
        (var.app_enabled && var.mode == "scaling" && var.min_size == 1 && var.desired_capacity == 1 && var.max_size == 4)
      )
      error_message = "ASG capacity must be 0/0/0 while disabled, 1/1/1 for performance, 2/2/2 for distributed-lock, or 1/1/4 for scaling."
    }
  }

  timeouts {
    delete = "15m"
  }
}

resource "aws_autoscaling_policy" "cpu" {
  count = var.scaling_enabled ? 1 : 0

  name                      = "${var.name_prefix}-app-cpu-50"
  autoscaling_group_name    = aws_autoscaling_group.app.name
  policy_type               = "TargetTrackingScaling"
  estimated_instance_warmup = 180

  target_tracking_configuration {
    target_value     = 50
    disable_scale_in = false

    predefined_metric_specification {
      predefined_metric_type = "ASGAverageCPUUtilization"
    }
  }
}

resource "aws_autoscaling_policy" "request_count" {
  count = var.scaling_enabled ? 1 : 0

  name                      = "${var.name_prefix}-app-request-count"
  autoscaling_group_name    = aws_autoscaling_group.app.name
  policy_type               = "TargetTrackingScaling"
  estimated_instance_warmup = 180

  target_tracking_configuration {
    target_value     = var.request_count_per_target_per_minute
    disable_scale_in = false

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = var.alb_resource_label
    }
  }
}
