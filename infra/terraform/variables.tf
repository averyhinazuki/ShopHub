# ── Provider / region ──────────────────────────────────────────────────────
variable "aws_region" {
  description = "AWS region to deploy into."
  type        = string
  default     = "us-east-1"
}

# ── Networking / access ────────────────────────────────────────────────────
variable "ssh_allowed_cidr" {
  description = <<-EOT
    CIDR allowed to SSH (port 22). Lock to your own IP as a /32 (find it with
    `curl ifconfig.me`). The default is an RFC 5737 documentation address:
    valid for `terraform plan` but matches no real client, so SSH is effectively
    closed until overridden in terraform.tfvars.
  EOT
  type        = string
  default     = "203.0.113.0/32"
}

# ── Compute ─────────────────────────────────────────────────────────────────
variable "instance_type" {
  description = <<-EOT
    EC2 instance type. Defaults to t3.small (2 GiB); the full compose stack
    OOMs a 1 GiB t3.micro once the JVM heap, MySQL buffer pool, and Redis are
    all resident. Drop to t3.micro only if the stack is slimmed (e.g. MySQL on
    RDS). Cost trade-off documented in docs/DEVOPS_ROADMAP.md.
  EOT
  type        = string
  default     = "t3.small"
}

variable "key_pair_name" {
  description = "Name to register the EC2 key pair under in AWS. A label only."
  type        = string
  default     = "shophub-key"
}

variable "public_key_path" {
  description = <<-EOT
    Path to the public key (.pub) installed on the instance for SSH. Derive it
    from an existing .pem with:
      ssh-keygen -y -f shophub-key.pem > shophub-key.pub
    AWS key pair names must be unique per region/account.
  EOT
  type        = string
  default     = "~/shophub-key.pub"
}

# ── Application image / secrets ────────────────────────────────────────────
variable "app_image" {
  description = "GHCR image reference for the ShopHub app (published by CI)."
  type        = string
  default     = "ghcr.io/averyhinazuki/shophub:latest"
}

variable "ghcr_token" {
  description = <<-EOT
    Optional GHCR token (read:packages) for `docker login` before pulling the
    image. Leave blank if the package is public.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "ghcr_username" {
  description = "GitHub username for GHCR login. Only used if ghcr_token is set."
  type        = string
  default     = ""
}

variable "mysql_root_password" {
  description = <<-EOT
    Root password for the MySQL container. No default — set in terraform.tfvars
    (gitignored). Note it lands in Terraform state; use an encrypted remote
    backend past learning scale, and consider SSM Parameter Store / Secrets
    Manager (see docs/DEVOPS_ROADMAP.md).
  EOT
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "Secret signing JWTs (app.jwt.secret). No default — set in terraform.tfvars. Generate with `openssl rand -base64 48`."
  type        = string
  sensitive   = true
}

variable "grafana_admin_password" {
  description = "Admin password for the Grafana UI (viewed via SSM port-forward)."
  type        = string
  sensitive   = true
}
