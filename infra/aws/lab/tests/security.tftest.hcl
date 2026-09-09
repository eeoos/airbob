mock_provider "aws" {}

run "monitoring_can_scrape_app_host_without_public_ingress" {
  command = plan

  module {
    source = "./modules/security"
  }

  variables {
    name_prefix          = "airbob-lab-security-test"
    vpc_id               = "vpc-0123456789abcdef0"
    private_subnet_cidrs = { a = "10.42.10.0/24" }
    dns_mode             = "direct-only"
    alb_ingress_cidr     = "8.8.8.8/32"
    tags                 = {}
  }

  override_resource {
    target          = aws_security_group.this["app"]
    override_during = plan
    values = {
      id = "sg-0123456789abcdef0"
    }
  }

  override_resource {
    target          = aws_security_group.this["monitoring"]
    override_during = plan
    values = {
      id = "sg-0123456789abcdef1"
    }
  }

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.referenced["app-monitoring-9100"].security_group_id == aws_security_group.this["app"].id &&
      aws_vpc_security_group_ingress_rule.referenced["app-monitoring-9100"].referenced_security_group_id == aws_security_group.this["monitoring"].id &&
      aws_vpc_security_group_ingress_rule.referenced["app-monitoring-9100"].ip_protocol == "tcp" &&
      aws_vpc_security_group_ingress_rule.referenced["app-monitoring-9100"].from_port == 9100 &&
      aws_vpc_security_group_ingress_rule.referenced["app-monitoring-9100"].to_port == 9100 &&
      aws_vpc_security_group_ingress_rule.referenced["app-monitoring-9100"].cidr_ipv4 == null &&
      aws_vpc_security_group_ingress_rule.referenced["app-monitoring-9100"].cidr_ipv6 == null
    )
    error_message = "App node-exporter must accept TCP 9100 from the monitoring group without public IPv4 or IPv6 ingress."
  }
}
