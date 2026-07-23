# ── Provider ──────────────────────────────────────────────────────────────
provider "aws" {
  region = var.aws_region
  # Credentials come from the environment or an `aws configure` profile — none
  # are set here. Use a least-privilege IAM user for Terraform (see docs/DEPLOY.md).
}

# ── Networking ────────────────────────────────────────────────────────────
# Reuse the region's default VPC rather than managing a custom one.
data "aws_vpc" "default" {
  default = true
}

# Pick the subnet by AZ, not sort order: the instance must land in the same AZ
# as the persistent data volume (EBS volumes are AZ-bound). Deriving the subnet
# from the volume's AZ makes a zone mismatch impossible.
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

# ── Persistent data volume (owned by infra/terraform/persistent/) ─────────
# A `data` block, not a resource: this stack attaches the volume but cannot
# delete or replace it, so `terraform destroy` here leaves the database intact.
# Looked up by tag to avoid copying volume IDs between states.
# Errors with "no matching EBS Volume found" if the persistent layer isn't applied.
data "aws_ebs_volume" "data" {
  filter {
    name   = "tag:Name"
    values = ["shophub-data"]
  }
}

# ── AMI ───────────────────────────────────────────────────────────────────
# Resolve the latest AL2023 standard image via SSM parameter. A name-glob data
# source was previously used and matched "al2023-ami-minimal-*" (published
# minutes later, so most_recent picked it); the minimal AMI lacks the SSM agent,
# which broke CI deploys with "InvalidInstanceId". The SSM parameter has no glob
# to get wrong. AMI changes replace the instance — cheap now that the databases
# live on a volume outside this state.
data "aws_ssm_parameter" "al2023" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
}

# ── SSH key pair ──────────────────────────────────────────────────────────
# Registers the public key with AWS; cloud-init installs it into
# ~/.ssh/authorized_keys. See variables.tf (public_key_path).
resource "aws_key_pair" "shophub" {
  key_name   = var.key_pair_name
  public_key = file(pathexpand(var.public_key_path))
}

# ── EC2 instance ──────────────────────────────────────────────────────────
resource "aws_instance" "shophub" {
  ami                    = data.aws_ssm_parameter.al2023.value
  instance_type          = var.instance_type
  subnet_id              = local.subnet_id
  key_name               = aws_key_pair.shophub.key_name
  vpc_security_group_ids = [aws_security_group.shophub.id]

  # Lets the SSM agent register so CI can deploy via `aws ssm send-command`
  # with no inbound SSH (see iam.tf).
  iam_instance_profile = aws_iam_instance_profile.shophub.name

  # Direct public access; the security group gates it.
  associate_public_ip_address = true

  # Must be >= the AMI snapshot size (this AL2023 build ships 30 GiB); a smaller
  # value fails RunInstances with InvalidBlockDeviceMapping.
  root_block_device {
    volume_size = 30
    volume_type = "gp3"
  }

  user_data = templatefile("${path.module}/user_data.sh.tpl", {
    app_image              = var.app_image
    ghcr_username          = var.ghcr_username
    ghcr_token             = var.ghcr_token
    mysql_root_password    = var.mysql_root_password
    jwt_secret             = var.jwt_secret
    docker_compose_content = file("${path.module}/../../docker-compose.cloud.yml")

    # Stable device path for the data volume. On Nitro instances the requested
    # /dev/sdf name isn't honoured and NVMe numbering is attach-order dependent,
    # so both are guesses. The volume ID is exposed as the NVMe serial (hyphen
    # stripped), which udev turns into a deterministic by-id symlink.
    data_volume_serial = replace(data.aws_ebs_volume.data.id, "-", "")
  })

  # cloud-init only runs user-data on first boot, so force a replace when the
  # rendered script changes. Safe here because the databases live on the EBS
  # volume attached below, outside this state.
  user_data_replace_on_change = true

  tags = {
    Name    = "shophub"
    Project = "shophub"
  }
}

# ── Data volume attachment ────────────────────────────────────────────────
# This stack owns the attachment, not the volume — destroying detaches, never
# deletes. Device name is advisory on Nitro (see data_volume_serial above).
resource "aws_volume_attachment" "data" {
  device_name = "/dev/sdf"
  volume_id   = data.aws_ebs_volume.data.id
  instance_id = aws_instance.shophub.id

  # Instance replacement detaches cleanly, but a targeted destroy against a live
  # box would hang without this. ext4 journals, so worst case is a recovery on
  # next mount.
  force_detach = true
}

# ── Elastic IP ────────────────────────────────────────────────────────────
# The address lives in infra/terraform/persistent/ so teardown doesn't release
# it. This stack owns only the association — the disposable half.
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
