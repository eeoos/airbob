resource "aws_route53_zone_association" "private" {
  zone_id    = local.lab_contract.private_dns_zone_id
  vpc_id     = module.network.vpc_id
  vpc_region = var.aws_region
}

locals {
  private_dns_records = local.services_enabled ? {
    "redis-general.lab.airbob.internal" = module.service_hosts.private_ips.redis
    "redis-cache.lab.airbob.internal"   = module.service_hosts.private_ips.redis
    "kafka.lab.airbob.internal"         = module.service_hosts.private_ips.kafka
    "connect.lab.airbob.internal"       = module.service_hosts.private_ips.debezium
    "elasticsearch.lab.airbob.internal" = module.service_hosts.private_ips.elasticsearch
    "monitoring.lab.airbob.internal"    = module.service_hosts.private_ips.monitoring
  } : {}
}

resource "aws_route53_record" "private_service" {
  for_each = local.private_dns_records

  zone_id = local.lab_contract.private_dns_zone_id
  name    = each.key
  type    = "A"
  ttl     = 30
  records = [each.value]

  depends_on = [aws_route53_zone_association.private]
}
