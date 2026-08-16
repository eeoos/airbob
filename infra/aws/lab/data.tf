data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_ssm_parameter" "foundation_contract" {
  name            = "/airbob/performance-lab/foundation/lab-contract"
  with_decryption = false
}
