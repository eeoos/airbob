output "instance_ids" {
  value = { for key, instance in aws_instance.this : key => instance.id }
}

output "private_ips" {
  value = { for key, instance in aws_instance.this : key => instance.private_ip }
}

output "primary_network_interface_ids" {
  value = { for key, instance in aws_instance.this : key => instance.primary_network_interface_id }
}
