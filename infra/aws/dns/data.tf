data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_ssm_parameter" "foundation_contract" {
  name            = "/airbob/performance-lab/foundation/dns-contract"
  with_decryption = false
}

data "aws_lb" "api" {
  count = var.aws_alb_arn == null ? 0 : 1

  arn = var.aws_alb_arn
}
