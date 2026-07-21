# ── Persistent (stateful) layer ─────────────────────────────────────────────
# This is a SEPARATE Terraform configuration with its OWN state file, and that
# separation is the entire point of the directory.
#
# The app stack in ../ is disposable by design: `ami` moves whenever AWS
# publishes a new AL2023, and `user_data_replace_on_change = true` means
# editing the boot script replaces the instance. That is fine — a web server
# should be cattle. But the DATABASE lived on that instance's root EBS volume,
# so "replace the box" and "delete the database" were the same operation.
#
# Terraform can only destroy what it tracks. Keeping the data volume in a
# different state file means `terraform destroy` in ../ physically cannot
# touch it — no `prevent_destroy` flag to remember, no error to work around,
# no temptation to delete the guardrail when it gets in the way. The app stack
# finds this volume with a `data` block (read-only) and attaches it.
#
# This is the standard "split stateful from stateless" layering. Real teams do
# the same thing at larger scale: a rarely-touched foundation/bootstrap state
# (VPC, IAM, RDS, DNS zones) that changes a few times a year, and per-service
# states that deploy many times a day.
#
#   cd infra/terraform/persistent && terraform init && terraform apply
#
# You should need to run this approximately once. If you find yourself running
# it often, something belongs in the app stack instead.

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
  description = "Must match the app stack's region — an EBS volume can only attach to an instance in its own region and AZ."
  type        = string
  default     = "us-east-1"
}

variable "volume_size" {
  description = <<-EOT
    Size of the data volume in GiB. 10 GiB is ample for this project's MySQL +
    MongoDB footprint and costs about $0.80/month at gp3 pricing.

    Note that you pay for an EBS volume whether or not it is attached to a
    running instance — exactly like the Elastic IP. That standing charge is
    the price of the data outliving the instance; if that is not a trade you
    want, the alternative is scheduled mysqldump-to-S3 and accepting a manual
    restore.

    GROWING this value is an in-place update (the filesystem still needs
    `growpart`/`resize2fs` on the box afterwards). SHRINKING it forces
    replacement, which destroys the data — Terraform will tell you it plans to
    replace, so read the plan.
  EOT
  type        = number
  default     = 10
}

# ── Placement ───────────────────────────────────────────────────────────────
# An EBS volume is pinned to a single Availability Zone and can only attach to
# an instance in that same AZ. The app stack resolves its subnet FROM this
# volume's AZ (see ../main.tf), so the dependency runs one way only: this
# layer picks the AZ, the app stack follows it. That ordering is deliberate —
# the stateful thing cannot move, so it gets to decide.

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Same deterministic pick the app stack used before this split existed, so
# creating the volume does not relocate an already-running instance.
data "aws_subnet" "chosen" {
  id = sort(data.aws_subnets.default.ids)[0]
}

# ── The volume ──────────────────────────────────────────────────────────────
resource "aws_ebs_volume" "data" {
  availability_zone = data.aws_subnet.chosen.availability_zone
  size              = var.volume_size
  type              = "gp3"

  # Encryption at rest, using the account's default EBS KMS key. Free, and it
  # cannot be turned on later without recreating the volume — so it goes on
  # now, while the volume is still empty.
  encrypted = true

  tags = {
    Name    = "shophub-data"
    Project = "shophub"
  }

  # Belt as well as braces. The state separation is the real protection; this
  # catches the case where someone runs `terraform destroy` from INSIDE this
  # directory, which the separation alone does nothing to prevent.
  #
  # To genuinely retire the volume: delete this block, apply, then destroy.
  lifecycle {
    prevent_destroy = true
  }
}

output "volume_id" {
  description = "Referenced by the app stack via a tag-filtered data source, not by this ID — printed for console lookups and manual snapshots."
  value       = aws_ebs_volume.data.id
}

output "availability_zone" {
  description = "The AZ the app stack's instance will be pinned to."
  value       = aws_ebs_volume.data.availability_zone
}
