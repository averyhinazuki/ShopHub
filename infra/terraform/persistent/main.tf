# ── Persistent (stateful) layer ─────────────────────────────────────────────
# A separate Terraform configuration with its own state file. The app stack in
# ../ is disposable (AMI moves, user_data changes replace the instance); the
# data must not be. Keeping the volume and EIP in a state the app stack can't
# reach means `terraform destroy` there cannot touch them. The app stack reads
# these via `data` blocks and attaches them.
#
# Standard "split stateful from stateless" layering — a rarely-changed
# foundation state plus a frequently-deployed app state.
#
#   cd infra/terraform/persistent && terraform init && terraform apply
#
# Applied rarely; if you run it often, something belongs in the app stack.

terraform {
  required_version = ">= 1.5"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  description = "Must match the app stack's region — an EBS volume attaches only within its own region and AZ."
  type        = string
  default     = "us-east-1"
}

variable "volume_size" {
  description = <<-EOT
    Data volume size in GiB. 10 GiB is ample for MySQL + MongoDB here (~$0.80/mo
    at gp3). Billed whether or not attached. Growing is in-place (then
    growpart/resize2fs on the box); shrinking forces replacement and destroys
    the data — read the plan.
  EOT
  type        = number
  default     = 10
}

# ── Placement ───────────────────────────────────────────────────────────────
# An EBS volume is pinned to one AZ. The app stack derives its subnet from this
# volume's AZ (../main.tf), so the stateful layer picks the AZ and the app stack
# follows.
data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Same deterministic pick the app stack used before the split, so creating the
# volume doesn't relocate a running instance.
data "aws_subnet" "chosen" {
  id = sort(data.aws_subnets.default.ids)[0]
}

# ── Data volume ─────────────────────────────────────────────────────────────
resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.chosen.availability_zone
  size              = var.volume_size
  type              = "gp3"

  # Encryption at rest (default EBS KMS key). Free, and cannot be enabled later
  # without recreating the volume — so on now, while empty.
  encrypted = true

  tags = {
    Name    = "shophub-data"
    Project = "shophub"
  }

  # Guards against a `terraform destroy` run from inside this directory, which
  # the state separation alone doesn't prevent. To retire: delete this block,
  # apply, then destroy.
  lifecycle {
    prevent_destroy = true
  }
}

# ── Elastic IP ──────────────────────────────────────────────────────────────
# Lives here so teardown of the app stack doesn't release the address (which
# would change the IP and invalidate the README/DNS). The app stack attaches it
# with an aws_eip_association.
#
# Cost: AWS bills every public IPv4 at $0.005/hr (~$3.60/mo) whether attached or
# not — the standing price of a stable address. To avoid it, move this back to
# ../main.tf as a plain aws_eip and accept a new IP after every destroy.
resource "aws_eip" "shophub" {
  domain = "vpc"

  tags = {
    Name    = "shophub"
    Project = "shophub"
  }

  lifecycle {
    prevent_destroy = true
  }
}

output "volume_id" {
  description = "Referenced by the app stack via tag, not this ID — printed for console lookups and snapshots."
  value       = aws_ebs_volume.data.id
}

output "availability_zone" {
  description = "The AZ the app stack's instance will be pinned to."
  value       = aws_ebs_volume.data.availability_zone
}

output "public_ip" {
  description = "The stable address. Survives `terraform destroy` of the app stack."
  value       = aws_eip.shophub.public_ip
}
