terraform {
  required_version = "= 1.15.5"

  required_providers {
    archive = {
      source  = "hashicorp/archive"
      version = "= 2.8.0"
    }

    aws = {
      source  = "hashicorp/aws"
      version = "= 6.55.0"
    }
  }
}
