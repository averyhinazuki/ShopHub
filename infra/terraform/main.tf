# ── Provider ────────────────────────────────────────────────────────────────

provider "aws" {
  region = var.aws_region

  # No access keys here on purpose. `terraform apply` picks up credentials
  # from the environment (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY) or an
  # `aws configure` profile. Create a dedicated IAM user for Terraform
  # (least-privilege — EC2 + VPC read/write, not AdministratorAccess) rather
  # than using your root account or personal user; see docs/DEPLOY.md.
}

# ── Networking: reuse the account's default VPC ───────────────────────────
# A learner project doesn't need a custom VPC with its own subnets, route
# tables and NAT gateway — that's real complexity for zero benefit at this
# stage, and a NAT gateway alone costs more per month than this whole EC2
# instance. Every AWS region starts with a default VPC that already has
# public subnets and an internet gateway wired up; we just read it with data
# sources instead of managing it.

data "aws_vpc" "default" {
  default = true
}

data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
}

# Pick one subnet deterministically (first by ID) — we only need a single
# placement for a single instance, so we don't need to reason about spreading
# across AZs the way a highly-available deployment would.
locals {
  subnet_id = sort(data.aws_subnets.default.ids)[0]
}

# ── AMI: always resolve the latest Amazon Linux 2023 image ────────────────
# Hardcoding an AMI ID would silently go stale (security patches, and
# eventually the AMI gets deprecated). Querying by name pattern + owner at
# plan time means every `terraform apply` uses whatever AL2023 build is
# current, at the cost of the instance being replaced if the AMI changes
# between applies (acceptable for a learning box; you'd pin this for prod).
data "aws_ami" "al2023" {
  most_recent = true
  owners      = ["amazon"] # restrict to Amazon's own account, not arbitrary public AMIs

  filter {
    name   = "name"
    values = ["al2023-ami-*-x86_64"]
  }

  filter {
    name   = "virtualization-type"
    values = ["hvm"]
  }
}

# ── SSH key pair ────────────────────────────────────────────────────────────
# Registers your PUBLIC key with AWS so it gets baked into the instance's
# ~/.ssh/authorized_keys via EC2's built-in cloud-init handling — no manual
# copying needed. See variables.tf (public_key_path) for how to derive this
# file from the .pem you already have.
resource "aws_key_pair" "shophub" {
  key_name   = var.key_pair_name
  public_key = file(pathexpand(var.public_key_path))
}

# ── EC2 instance ─────────────────────────────────────────────────────────
resource "aws_instance" "shophub" {
  ami                    = data.aws_ami.al2023.id
  instance_type          = var.instance_type
  subnet_id              = local.subnet_id
  key_name               = aws_key_pair.shophub.key_name
  vpc_security_group_ids = [aws_security_group.shophub.id]

  # Without this, the instance only gets a private IP inside the default VPC
  # and you'd need a bastion/VPN to reach it. Fine for a learning project to
  # expose directly; the security group is what actually gates access.
  associate_public_ip_address = true

  # Must be >= the AMI's snapshot size — this AL2023 build ships a 30 GiB
  # baseline, and AWS rejects any root volume smaller than the snapshot it's
  # created from (a 20 GiB volume here fails RunInstances with
  # InvalidBlockDeviceMapping). 30 GiB also leaves ample room for Docker
  # images (JDK base, MySQL, Redis, nginx).
  root_block_device {
    volume_size = 30
    volume_type = "gp3" # gp3 is cheaper than gp2 at the same baseline performance
  }

  # Rendered once at boot by cloud-init (AL2023 ships cloud-init out of the
  # box). This is where "provisioning" (create the box) hands off to
  # "configuration" (install Docker, start the stack) — see user_data.sh.tpl.
  user_data = templatefile("${path.module}/user_data.sh.tpl", {
    app_image              = var.app_image
    ghcr_username          = var.ghcr_username
    ghcr_token             = var.ghcr_token
    mysql_root_password    = var.mysql_root_password
    jwt_secret             = var.jwt_secret
    docker_compose_content = file("${path.module}/../../docker-compose.cloud.yml")
    nginx_conf_content     = file("${path.module}/../nginx/nginx.conf")
  })

  # Changing user_data alone doesn't re-run it on an already-booted instance
  # (cloud-init only runs user-data on first boot). This forces a replace
  # when the rendered script changes, so `terraform apply` after editing the
  # compose file or nginx config actually reaches the box instead of
  # silently no-op'ing. Fine for a single learner instance; you would NOT
  # want this on a stateful prod box (it destroys and recreates the EC2
  # instance, losing anything not in a volume/DB outside it).
  user_data_replace_on_change = true

  tags = {
    Name    = "shophub"
    Project = "shophub"
  }
}

# ── Elastic IP ──────────────────────────────────────────────────────────
# A plain public IP on the instance changes if you stop/start it. Attaching
# an EIP gives you a stable address so you don't have to update DNS/bookmarks
# after every restart. Note: AWS bills an EIP hourly while it's allocated but
# NOT attached to a running instance — don't `terraform destroy` the instance
# and leave the EIP dangling, or `terraform destroy` everything together.
resource "aws_eip" "shophub" {
  instance = aws_instance.shophub.id
  domain   = "vpc"

  tags = {
    Name    = "shophub"
    Project = "shophub"
  }
}
