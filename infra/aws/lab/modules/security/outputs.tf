output "security_group_ids" {
  value = { for key, security_group in aws_security_group.this : key => security_group.id }
}

output "referenced_ingress_contract" {
  value = local.referenced_ingress_rules
}
