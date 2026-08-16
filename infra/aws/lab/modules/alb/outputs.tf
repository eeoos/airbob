output "arn" {
  value = aws_lb.app.arn
}

output "arn_suffix" {
  value = aws_lb.app.arn_suffix
}

output "dns_name" {
  value = aws_lb.app.dns_name
}

output "zone_id" {
  value = aws_lb.app.zone_id
}

output "target_group_arn" {
  value = aws_lb_target_group.app.arn
}

output "target_group_arn_suffix" {
  value = aws_lb_target_group.app.arn_suffix
}

output "refresh_alarm_name" {
  value = aws_cloudwatch_metric_alarm.app_target_5xx.alarm_name
}

output "resource_label" {
  value = "${aws_lb.app.arn_suffix}/${aws_lb_target_group.app.arn_suffix}"
}

output "contract" {
  value = {
    https_only           = aws_lb_listener.https.port == 443 && aws_lb_listener.https.protocol == "HTTPS"
    stickiness_enabled   = one(aws_lb_target_group.app.stickiness).enabled
    health_check_path    = one(aws_lb_target_group.app.health_check).path
    deregistration_delay = aws_lb_target_group.app.deregistration_delay
  }
}
