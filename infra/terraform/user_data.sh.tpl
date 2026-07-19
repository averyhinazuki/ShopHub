#!/bin/bash
# Rendered by Terraform's templatefile() (see main.tf) and run once by
# cloud-init on first boot, as root. This is the "configuration" half of the
# provisioning/configuration split: Terraform created the box, this script
# turns it into a running ShopHub deployment.
#
# Logs land in /var/log/cloud-init-output.log — `ssh` in and
# `sudo tail -f /var/log/cloud-init-output.log` if something looks stuck.
set -euxo pipefail

# ── Docker + the Compose plugin ─────────────────────────────────────────
# Amazon Linux 2023 uses dnf, not yum, and ships a `docker` package but NOT
# the `docker compose` v2 plugin by default — that's a separate package.
dnf update -y
dnf install -y docker
systemctl enable --now docker

# `docker compose` (v2, no hyphen) is the compose plugin, distinct from the
# old standalone `docker-compose` v1 binary. AL2023's repos ship `docker` but
# NOT the compose plugin (that package lives in Docker's own CE repo), so the
# plugin is installed as a standalone binary into Docker's cli-plugins dir.
# Trade-off: `dnf update` won't patch it; bump the pinned version here instead.
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL https://github.com/docker/compose/releases/download/v2.39.1/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version   # fail fast (set -e) if the plugin didn't install

# Let ec2-user run docker without sudo — convenience for interactive
# debugging over SSH; the compose stack itself is started below as root
# since cloud-init user-data always runs as root anyway.
usermod -aG docker ec2-user

# ── App directory + the compose stack ───────────────────────────────────
mkdir -p /opt/shophub
cd /opt/shophub

# docker-compose.cloud.yml is embedded at `terraform apply` time via
# templatefile(..., { docker_compose_content = file(...) }) in main.tf, so the
# instance doesn't need git/network access to this repo to stand up the stack
# — it just needs to reach GHCR and the OS package repos. (nginx.conf and the
# built frontend are no longer written here at all: they're baked into the
# shophub-frontend image by CI — see Dockerfile.frontend — and arrive via
# `docker compose pull` like the app image.)
cat > docker-compose.cloud.yml <<'COMPOSE_EOF'
${docker_compose_content}
COMPOSE_EOF

# Secrets go in a .env file, NOT into docker-compose.cloud.yml itself — the
# compose file is tracked in git (fine, no secrets in it); this .env is
# generated fresh on every instance and never leaves it. `docker compose`
# automatically reads a .env file in the same directory for variable
# substitution (the $${VAR} references in docker-compose.cloud.yml).
#
# The heredoc delimiter is QUOTED ('ENV_EOF') even though we want Terraform's
# $${...} values substituted: that substitution already happened above, at
# `terraform apply` time, before bash ever sees this script. Quoting here
# only controls whether *bash* re-expands the resulting text, and it must
# not — if a generated password happens to contain a literal $ or backtick,
# an unquoted heredoc would mangle it via bash's own expansion.
cat > .env <<'ENV_EOF'
APP_IMAGE=${app_image}
MYSQL_ROOT_PASSWORD=${mysql_root_password}
JWT_SECRET=${jwt_secret}
ENV_EOF
chmod 600 .env

# ── (Optional) GHCR login ────────────────────────────────────────────────
# Only needed if ghcr.io/<owner>/shophub is a PRIVATE package. Prefer making
# it public (GitHub package settings) so this whole step — and the token
# variable in terraform.tfvars — can go away entirely.
if [ -n "${ghcr_token}" ]; then
  echo "${ghcr_token}" | docker login ghcr.io -u "${ghcr_username}" --password-stdin
fi

# ── Start the stack ─────────────────────────────────────────────────────
docker compose -f docker-compose.cloud.yml --env-file .env pull
docker compose -f docker-compose.cloud.yml --env-file .env up -d
