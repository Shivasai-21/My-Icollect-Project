terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.60, < 7.0"
    }
  }

  # Local state is fine for a lab you destroy after every session.
  # For a team, move it to S3 with locking:
  #   backend "s3" { bucket = "..." key = "icollect/eks/terraform.tfstate" region = "ap-south-1" use_lockfile = true }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "icollect"
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}
