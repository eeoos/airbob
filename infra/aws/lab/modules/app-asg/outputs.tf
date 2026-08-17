output "name" {
  value = aws_autoscaling_group.app.name
}

output "contract" {
  value = {
    instance_type             = aws_launch_template.app.instance_type
    min                       = aws_autoscaling_group.app.min_size
    desired                   = aws_autoscaling_group.app.desired_capacity
    max                       = aws_autoscaling_group.app.max_size
    subnet_count              = length(var.subnet_ids)
    default_instance_warmup   = aws_autoscaling_group.app.default_instance_warmup
    detailed_monitoring       = one(aws_launch_template.app.monitoring).enabled
    scaling_policy_count      = length(aws_autoscaling_policy.cpu) + length(aws_autoscaling_policy.request_count)
    cpu_target_percent        = 50
    request_target_per_minute = var.request_count_per_target_per_minute
    refresh = {
      min_healthy_percentage = var.mode == "performance" ? 0 : 100
      max_healthy_percentage = var.mode == "performance" ? 100 : 200
      checkpoint_percentages = var.mode == "scaling" ? [50, 100] : []
      auto_rollback          = true
    }
  }
}
