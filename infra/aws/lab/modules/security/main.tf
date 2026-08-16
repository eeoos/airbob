locals {
  security_group_names = toset([
    "alb",
    "app",
    "rds",
    "redis",
    "kafka",
    "debezium",
    "elasticsearch",
    "monitoring",
    "loadgen",
    "nat",
    "probe",
  ])

  referenced_ingress_rules = {
    app-alb-8080          = { target = "app", source = "alb", port = 8080 }
    app-monitoring-8080   = { target = "app", source = "monitoring", port = 8080 }
    rds-app-3306          = { target = "rds", source = "app", port = 3306 }
    rds-debezium-3306     = { target = "rds", source = "debezium", port = 3306 }
    rds-loadgen-3306      = { target = "rds", source = "loadgen", port = 3306 }
    redis-app-6379        = { target = "redis", source = "app", port = 6379 }
    redis-app-6380        = { target = "redis", source = "app", port = 6380 }
    redis-monitoring-9121 = { target = "redis", source = "monitoring", port = 9121 }
    redis-monitoring-9122 = { target = "redis", source = "monitoring", port = 9122 }
    redis-monitoring-9100 = { target = "redis", source = "monitoring", port = 9100 }
    kafka-app-9092        = { target = "kafka", source = "app", port = 9092 }
    kafka-debezium-9092   = { target = "kafka", source = "debezium", port = 9092 }
    kafka-monitoring-7071 = { target = "kafka", source = "monitoring", port = 7071 }
    kafka-monitoring-9100 = { target = "kafka", source = "monitoring", port = 9100 }
    debezium-monitor-9404 = { target = "debezium", source = "monitoring", port = 9404 }
    debezium-monitor-9100 = { target = "debezium", source = "monitoring", port = 9100 }
    es-app-9200           = { target = "elasticsearch", source = "app", port = 9200 }
    es-debezium-9200      = { target = "elasticsearch", source = "debezium", port = 9200 }
    es-loadgen-9200       = { target = "elasticsearch", source = "loadgen", port = 9200 }
    es-monitoring-9114    = { target = "elasticsearch", source = "monitoring", port = 9114 }
    es-monitoring-9100    = { target = "elasticsearch", source = "monitoring", port = 9100 }
    monitor-self-9100     = { target = "monitoring", source = "monitoring", port = 9100 }
  }
}

resource "aws_security_group" "this" {
  for_each = local.security_group_names

  name_prefix = "${var.name_prefix}-${each.key}-"
  description = "Airbob performance lab ${each.key}"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, {
    Name    = "${var.name_prefix}-${each.key}"
    Service = each.key
  })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_egress_rule" "all" {
  for_each = aws_security_group.this

  security_group_id = each.value.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Required outbound dependency access"
  tags              = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "alb_https" {
  security_group_id = aws_security_group.this["alb"].id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  description       = "Public HTTPS only"
  tags              = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "nat_private" {
  for_each = var.private_subnet_cidrs

  security_group_id = aws_security_group.this["nat"].id
  cidr_ipv4         = each.value
  ip_protocol       = "-1"
  description       = "Forward private subnet egress"
  tags              = var.tags
}

resource "aws_vpc_security_group_ingress_rule" "referenced" {
  for_each = local.referenced_ingress_rules

  security_group_id            = aws_security_group.this[each.value.target].id
  referenced_security_group_id = aws_security_group.this[each.value.source].id
  ip_protocol                  = "tcp"
  from_port                    = each.value.port
  to_port                      = each.value.port
  description                  = each.key
  tags                         = var.tags
}
