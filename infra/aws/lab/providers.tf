provider "aws" {
  region              = var.aws_region
  allowed_account_ids = [var.account_id]

  default_tags {
    tags = {
      Project     = "airbob"
      Environment = "performance-lab"
      Stack       = "lab"
      ManagedBy   = "terraform"
      Persistence = "ephemeral"
      ExpiresAt   = var.expires_at
    }
  }
}
