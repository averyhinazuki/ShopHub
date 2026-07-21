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

# The subnet is chosen by AVAILABILITY ZONE, not by sort order, because the
# instance has to land in the same AZ as the persistent data volume — EBS
# volumes cannot cross AZ boundaries. Deriving the subnet from the volume's AZ
# (rather than picking a subnet and hoping the volume matches) makes the
# constraint impossible to violate: if the volume ever moves, the instance
# follows it automatically instead of failing at attach time with
# "InvalidVolume.ZoneMismatch".
data "aws_subnets" "default" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }

  filter {
    name   = "availability-zone"
    values = [data.aws_ebs_volume.data.availability_zone]
  }
}

locals {
  subnet_id = sort(data.aws_subnets.default.ids)[0]
}

# ── Persistent data volume (owned by ANOTHER state file) ──────────────────
# Deliberately a `data` block, not a `resource`. The volume is created and
# owned by infra/terraform/persistent/, which has its own state — so this
# configuration can attach it and read its attributes, but has no power to
# delete or replace it. `terraform destroy` here tears down the instance, the
# EIP and the security group and leaves the database sitting in AWS, intact,
# waiting for the next apply to reattach it.
#
# That is the whole design: the box is cattle, the data is not, so they do not
# share a lifecycle. Looking it up by TAG rather than by volume ID means no ID
# has to be copied between the two configurations by hand.
#
# If this errors with "no matching EBS Volume found", the persistent layer has
# not been applied yet:
#   cd persistent && terraform init && terraform apply
data "aws_ebs_volume" "data" {
  filter {
    name   = "tag:Name"
    values = ["shophub-data"]
  }
}

# ── AMI: always resolve the latest Amazon Linux 2023 image ────────────────
# Hardcoding an AMI ID would silently go stale (security patches, and
# eventually the AMI gets deprecated), so this resolves at plan time — at the
# cost of the instance being replaced if the AMI changes between applies.
# That used to mean losing the database with it; now that MySQL and MongoDB
# live on a volume outside this state, replacement costs a few minutes of
# downtime and nothing else.
#
# This was previously a `data "aws_ami"` block filtering on the name pattern
# "al2023-ami-*-x86_64". That glob is a trap: it also matches
# "al2023-ami-MINIMAL-*", and AWS publishes the minimal images a few minutes
# AFTER the standard ones, so `most_recent = true` picked minimal every time.
# The minimal AMI ships without amazon-ssm-agent — the instance booted and
# served traffic fine, but never registered with Systems Manager, so the CI
# deploy job failed with:
#   InvalidInstanceId: Instances not in a valid state for account
#
# AWS publishes the canonical "latest AL2023, standard variant, default
# kernel" AMI ID as a public SSM parameter. Using it removes the glob
# ambiguity entirely — there is no pattern left to get subtly wrong, and no
# tie-break between same-timestamp kernel variants.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
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
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = local.subnet_id
  key_name               = aws_key_pair.shophub.key_name
  vpc_security_group_ids = [aws_security_group.shophub.id]

  # Lets the preinstalled SSM agent register with Systems Manager, so CI can
  # deploy via `aws ssm send-command` with no inbound SSH (see iam.tf).
  # Attaching/changing a profile is an in-place update, not a replacement.
  iam_instance_profile = aws_iam_instance_profile.shophub.name

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

    # Used to build a STABLE device path for the data volume. On Nitro
    # instances (t3/c7i/...) the "/dev/sdf" name requested below is not what
    # the kernel actually creates — EBS volumes surface as NVMe devices and
    # get numbered in attach order, so /dev/nvme1n1 is a guess, not a
    # guarantee. AWS does expose the volume ID as the NVMe serial, and udev
    # builds a symlink from it, which is deterministic:
    #   /dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_vol0abc123...
    # The serial drops the hyphen from the volume ID, hence the replace().
    data_volume_serial = replace(data.aws_ebs_volume.data.id, "-", "")
  })

  # Changing user_data alone doesn't re-run it on an already-booted instance
  # (cloud-init only runs user-data on first boot). This forces a replace
  # when the rendered script changes, so `terraform apply` after editing the
  # compose file or nginx config actually reaches the box instead of
  # silently no-op'ing. The caveat this comment used to carry — "you would NOT
  # want this on a stateful box, it loses anything not on a volume outside the
  # instance" — is now handled rather than merely noted: the databases live on
  # the EBS volume attached below, which this state does not own.
  user_data_replace_on_change = true

  tags = {
    Name    = "shophub"
    Project = "shophub"
  }
}

# ── Attach the data volume ──────────────────────────────────────────────
# Terraform owns the ATTACHMENT (a relationship, disposable) but not the
# VOLUME (the data, permanent). Destroying this stack detaches; it does not
# delete.
#
# The requested device name is advisory on Nitro instances — see the
# data_volume_serial comment above for why user-data addresses the disk by
# its udev by-id symlink instead of trusting this path.
resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = data.aws_ebs_volume.data.id
  instance_id = aws_instance.shophub.id

  # Detaching a volume with a mounted filesystem can hang, because the guest
  # OS never got told to unmount. Instance replacement terminates the
  # instance, which detaches cleanly on its own, so the common path is fine —
  # but a manual `terraform destroy -target=aws_volume_attachment.data`
  # against a live box would stall without this. ext4 journals, so the worst
  # case is a recovery on next mount rather than corruption.
  force_detach = true
}

# ── Elastic IP ──────────────────────────────────────────────────────────
# A plain public IP changes if you stop/start the instance; an EIP gives you a
# stable address. This used to be an `aws_eip` RESOURCE here, which meant
# `terraform destroy` released 52.4.114.4 back to AWS and the next apply came
# up on a different address — every teardown silently invalidated bookmarks,
# the README and any DNS pointing at it.
#
# The address now lives in infra/terraform/persistent/ with the data volume,
# for exactly the same reason: it is an identity, not a machine. This stack
# owns only the ASSOCIATION — the disposable half — so destroying it detaches
# the address and leaves it allocated for the next apply to pick back up.
data "aws_eip" "shophub" {
  filter {
    name   = "tag:Name"
    values = ["shophub"]
  }
}

resource "aws_eip_association" "shophub" {
  allocation_id = data.aws_eip.shophub.id
  instance_id   = aws_instance.shophub.id
}
