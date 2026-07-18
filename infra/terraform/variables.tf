# ── Provider / region ──────────────────────────────────────────────────────

variable "aws_region" {
  description = "AWS region to deploy into. us-east-1 has the broadest free-tier/credit service coverage."
  type        = string
  default     = "us-east-1"
}

# ── Networking / access ────────────────────────────────────────────────────

variable "ssh_allowed_cidr" {
  description = <<-EOT
    CIDR allowed to SSH (port 22) into the instance. Locking this to your own
    IP (instead of 0.0.0.0/0) is the single biggest "don't get your box
    cryptomined" win you get for free. Find yours with `curl ifconfig.me`
    and pass it as /32, e.g. "203.0.113.7/32".

    The default below (203.0.113.0/32) is a TEST-NET-3 address reserved for
    documentation (RFC 5737) — it is syntactically valid so `terraform plan`
    works out of the box, but it will never match a real client, so SSH is
    effectively closed until you override it in terraform.tfvars.
  EOT
  type        = string
  default     = "203.0.113.0/32"
}

# ── Compute ─────────────────────────────────────────────────────────────────

variable "instance_type" {
  description = <<-EOT
    EC2 instance type. Defaults to t3.small (2 GiB RAM), NOT the free-tier
    t2/t3.micro (1 GiB RAM). The full docker-compose stack — Spring Boot JVM
    + MySQL + Redis + nginx, even with Kafka/MongoDB trimmed out for this
    first deploy — comfortably OOMs a 1 GiB instance once the JVM heap,
    MySQL's buffer pool, and Redis are all resident at once. t3.small burns
    AWS credits/hourly cost instead of being free — that's the trade-off
    documented in docs/DEVOPS_ROADMAP.md. Drop to t3.micro only if you also
    slim the compose stack further (e.g. move MySQL off-box to RDS free tier).
  EOT
  type        = string
  default     = "t3.small"
}

variable "key_pair_name" {
  description = "Name to register the EC2 key pair under in AWS. Purely a label; doesn't need to match the local file name."
  type        = string
  default     = "shophub-key"
}

variable "public_key_path" {
  description = <<-EOT
    Path to the PUBLIC key (.pub) to install on the instance for SSH.

    You already have the PRIVATE key locally (shophub-key.pem, per prior
    manual EC2 setup) but Terraform's aws_key_pair resource needs the public
    key material, not the private key. If you only have the .pem:

      ssh-keygen -y -f C:\Users\hinazuki\shophub-key.pem > C:\Users\hinazuki\shophub-key.pub

    That derives the public key from the private key without needing AWS at
    all. Point this variable at the resulting .pub file. (If AWS still has a
    key pair of the same name from your earlier manual experiments, either
    reuse that key pair name below via key_pair_name and drop this resource,
    or pick a fresh key_pair_name — AWS key pair names must be unique per
    region/account.)
  EOT
  type        = string
  default     = "~/shophub-key.pub"
}

# ── Application image / secrets ────────────────────────────────────────────

variable "app_image" {
  description = "GHCR image reference for the ShopHub app, as published by CI (see .github/workflows/ci.yml)."
  type        = string
  default     = "ghcr.io/averyhinazuki/shophub:latest"
}

variable "ghcr_token" {
  description = <<-EOT
    Optional GHCR (GitHub Container Registry) personal access token with
    read:packages scope, used by the instance to `docker login` before
    pulling the image. Leave blank if the ghcr.io/<owner>/shophub package is
    public (Package settings -> Change visibility on github.com) — that's
    the simpler path and avoids putting a token in tfvars/state at all.
  EOT
  type        = string
  default     = ""
  sensitive   = true
}

variable "ghcr_username" {
  description = "GitHub username to log in to GHCR with. Only used if ghcr_token is set."
  type        = string
  default     = ""
}

variable "mysql_root_password" {
  description = <<-EOT
    Root password for the MySQL container. There is no safe default here on
    purpose — you must set this in terraform.tfvars. This is the "secrets
    management" gap called out in docs/DEVOPS_ROADMAP.md: it's already a
    step up from the CI workflow's hardcoded MYSQL_ROOT_PASSWORD, because it
    never lands in a Dockerfile or docker-compose.yml committed to git — but
    it still ends up in Terraform state, which is why state should live in
    an encrypted remote backend (S3 + KMS) once you go past learning-project
    scale, and why terraform.tfvars is gitignored. A future step is moving
    this into AWS SSM Parameter Store / Secrets Manager and having user-data
    fetch it via the instance's IAM role instead of passing it through
    Terraform variables at all.
  EOT
  type        = string
  sensitive   = true
}

variable "jwt_secret" {
  description = "Secret used to sign JWTs (app.jwt.secret in application.yml). No default — must be set in terraform.tfvars. Generate with e.g. `openssl rand -base64 48`."
  type        = string
  sensitive   = true
}
