# Pin the Terraform CLI and provider versions so `terraform init` gives you
# the same behavior today as it will in six months. Without this, a provider
# major-version bump could silently change resource schemas out from under you.
terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}
