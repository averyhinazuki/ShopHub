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

# ── SSM agent ───────────────────────────────────────────────────────────
# The standard AL2023 AMI ships this preinstalled and running, but the
# MINIMAL variant does not — and a too-broad AMI name glob in main.tf used to
# select minimal, producing a box that served traffic perfectly while every
# CI deploy failed with "InvalidInstanceId: Instances not in a valid state for
# account". main.tf now pins the standard AMI via SSM parameter, so this is
# redundant there; it stays as defence in depth so the deploy path can never
# again depend on which AMI variant got picked. Both commands are no-ops when
# the agent is already present and enabled.
dnf install -y amazon-ssm-agent
systemctl enable --now amazon-ssm-agent

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

# ── Persistent data volume ──────────────────────────────────────────────
# MySQL and MongoDB store their data on a separate EBS volume that is NOT
# owned by this Terraform state (see infra/terraform/persistent/), so it
# survives instance replacement. Everything below turns "a disk is attached"
# into "a filesystem is mounted at /data", exactly once, idempotently.
#
# Address the disk by its udev by-id symlink rather than /dev/sdf or
# /dev/nvme1n1. On Nitro instances the requested /dev/sdf name is not honoured
# and NVMe numbering depends on attach order, so both are guesses; the volume
# ID is baked into the NVMe serial and is not.
DEVICE="/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${data_volume_serial}"
MOUNT=/data

# The attachment is a separate API call that Terraform makes AFTER the
# instance exists, so at this point in first boot the disk may legitimately
# not be there yet. Wait rather than race.
for _ in $(seq 1 60); do
  if [ -e "$DEVICE" ]; then break; fi
  sleep 5
done

# Fail loudly if it never showed up. The tempting alternative — carry on and
# let Docker create /data on the root filesystem — would look like a healthy
# deploy while silently reintroducing the exact bug this volume exists to fix:
# a database that dies with the instance. A box that refuses to boot is much
# easier to notice than one quietly writing to the wrong disk.
if [ ! -e "$DEVICE" ]; then
  echo "FATAL: data volume $DEVICE never attached; refusing to start the stack" >&2
  exit 1
fi

# Format ONLY if there is no filesystem yet. This single condition is what
# separates "first ever boot" from "every rebuild afterwards" — an
# unconditional mkfs here would wipe the database on every instance
# replacement, which is precisely the failure this whole change exists to
# prevent. blkid prints the filesystem type and exits non-zero when the disk
# is raw, hence the `|| true` under `set -e`.
FSTYPE=$(blkid -p -s TYPE -o value "$DEVICE" 2>/dev/null || true)
if [ -z "$FSTYPE" ]; then
  echo "No filesystem on $DEVICE — first boot, formatting"
  mkfs -t ext4 -L shophub-data "$DEVICE"
else
  echo "Existing $FSTYPE filesystem on $DEVICE — preserving it"
fi

mkdir -p "$MOUNT"

# Persist the mount so a plain reboot (not just cloud-init's first boot)
# brings the data back. `nofail` keeps a missing disk from dropping the box
# into emergency mode where it is unreachable over SSH — a rescue-by-console
# situation on a machine with no console.
if ! grep -q "$DEVICE" /etc/fstab; then
  echo "$DEVICE $MOUNT ext4 defaults,nofail 0 2" >> /etc/fstab
fi
mount "$MOUNT"

# One directory per datastore, so the two never share a filesystem root and
# `du -sh /data/*` answers "who is using the disk".
mkdir -p "$MOUNT/mysql" "$MOUNT/mongo"

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
DATA_DIR=/data
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
