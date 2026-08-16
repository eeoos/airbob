resource "aws_route53_zone" "public" {
  name          = local.zone_name
  force_destroy = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_vpc" "private_dns_anchor" {
  cidr_block           = "10.255.255.240/28"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name = "airbob-private-dns-anchor"
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_route53_zone" "private" {
  name          = "lab.airbob.internal"
  force_destroy = false

  vpc {
    vpc_id     = aws_vpc.private_dns_anchor.id
    vpc_region = var.aws_region
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_route53_record" "static" {
  for_each = var.static_dns_records

  zone_id = aws_route53_zone.public.zone_id
  name    = each.value.name
  type    = upper(each.value.type)
  ttl     = each.value.ttl
  records = each.value.records

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_acm_certificate" "api" {
  domain_name       = local.api_fqdn
  validation_method = "DNS"
  key_algorithm     = "RSA_2048"

  lifecycle {
    prevent_destroy = true
  }
}

locals {
  api_certificate_validation = one(aws_acm_certificate.api.domain_validation_options)
}

resource "aws_route53_record" "api_certificate_validation" {
  zone_id = aws_route53_zone.public.zone_id
  name    = local.api_certificate_validation.resource_record_name
  type    = local.api_certificate_validation.resource_record_type
  ttl     = 60
  records = [local.api_certificate_validation.resource_record_value]

  allow_overwrite = false

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_acm_certificate_validation" "api" {
  count = var.dns_delegation_confirmed ? 1 : 0

  certificate_arn         = aws_acm_certificate.api.arn
  validation_record_fqdns = [aws_route53_record.api_certificate_validation.fqdn]

  timeouts {
    create = "20m"
  }

  lifecycle {
    prevent_destroy = true
  }
}
