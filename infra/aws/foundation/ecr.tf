locals {
  ecr_lifecycle_policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Remove untagged images after seven days"
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = 7
        }
        action = {
          type = "expire"
        }
      },
      {
        rulePriority = 2
        description  = "Retain the newest tagged images"
        selection = {
          tagStatus = "tagged"
          tagPatternList = [
            "*",
          ]
          countType   = "imageCountMoreThan"
          countNumber = var.ecr_tagged_image_count
        }
        action = {
          type = "expire"
        }
      },
    ]
  })
}

resource "aws_ecr_repository" "application" {
  name                 = "airbob-repo"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false

  encryption_configuration {
    encryption_type = "AES256"
  }

  image_scanning_configuration {
    scan_on_push = var.application_ecr_scan_on_push
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_ecr_lifecycle_policy" "application" {
  repository = aws_ecr_repository.application.name
  policy     = local.ecr_lifecycle_policy

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_ecr_repository" "infrastructure" {
  for_each = local.infra_ecr_repositories

  name                 = each.value
  image_tag_mutability = "IMMUTABLE"
  force_delete         = false

  encryption_configuration {
    encryption_type = "AES256"
  }

  image_scanning_configuration {
    scan_on_push = true
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_ecr_lifecycle_policy" "infrastructure" {
  for_each = aws_ecr_repository.infrastructure

  repository = each.value.name
  policy     = local.ecr_lifecycle_policy

  lifecycle {
    prevent_destroy = true
  }
}
