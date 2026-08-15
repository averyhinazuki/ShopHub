#!/bin/bash
# Rendered by templatefile() (main.tf) and run once by cloud-init on first boot
# as root. Turns the fresh instance into a running ShopHub deployment.
# Logs: /var/log/cloud-init-output.log
set -euxo pipefail

# ── Docker + Compose plugin ─────────────────────────────────────────────
# AL2023 uses dnf and ships `docker` but not the compose v2 plugin.
dnf update -y
dnf install -y docker
systemctl enable --now docker

# ── SSM agent ───────────────────────────────────────────────────────────
# Preinstalled on the standard AL2023 AMI (not minimal). main.tf pins the
# standard AMI, so this is defence-in-depth; both commands are no-ops when the
# agent is already present.
dnf install -y amazon-ssm-agent
systemctl enable --now amazon-ssm-agent

# Install the compose v2 plugin as a standalone binary (not in AL2023 repos).
# `dnf update` won't patch it — bump the pinned version here.
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL https://github.com/docker/compose/releases/download/v2.39.1/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version   # fail fast if the plugin didn't install

# Let ec2-user run docker without sudo (interactive debugging convenience).
usermod -aG docker ec2-user

# ── Persistent data volume ──────────────────────────────────────────────
# MySQL/MongoDB data lives on a separate EBS volume (infra/terraform/persistent/)
# that survives instance replacement. Mount it at /data, idempotently.
#
# Address the disk by its udev by-id symlink: on Nitro the requested /dev/sdf
# name isn't honoured and NVMe numbering is attach-order dependent, but the
# volume ID is baked into the NVMe serial.
DEVICE="/dev/disk/by-id/nvme-Amazon_Elastic_Block_Store_${data_volume_serial}"
MOUNT=/data

# The attachment is a separate API call Terraform makes after the instance
# exists, so the disk may not be present yet at first boot. Wait, don't race.
for _ in $(seq 1 60); do
  if [ -e "$DEVICE" ]; then break; fi
  sleep 5
done

# Fail loudly if it never attached — carrying on would let Docker create /data
# on the root filesystem, silently reintroducing the "database dies with the
# instance" bug this volume exists to prevent.
if [ ! -e "$DEVICE" ]; then
  echo "FATAL: data volume $DEVICE never attached; refusing to start the stack" >&2
  exit 1
fi

# Format only if there's no filesystem yet. An unconditional mkfs would wipe the
# database on every instance replacement. blkid exits non-zero on a raw disk.
FSTYPE=$(blkid -p -s TYPE -o value "$DEVICE" 2>/dev/null || true)
if [ -z "$FSTYPE" ]; then
  echo "No filesystem on $DEVICE — first boot, formatting"
  mkfs -t ext4 -L shophub-data "$DEVICE"
else
  echo "Existing $FSTYPE filesystem on $DEVICE — preserving it"
fi

mkdir -p "$MOUNT"

# Persist the mount across reboots. `nofail` keeps a missing disk from dropping
# the box into emergency mode (unreachable over SSH).
if ! grep -q "$DEVICE" /etc/fstab; then
  echo "$DEVICE $MOUNT ext4 defaults,nofail 0 2" >> /etc/fstab
fi
mount "$MOUNT"

# One directory per datastore.
mkdir -p "$MOUNT/mysql" "$MOUNT/mongo" "$MOUNT/prometheus" "$MOUNT/grafana" "$MOUNT/redis"

# Prometheus (uid 65534), Grafana (uid 472) and Redis (uid 999) don't self-chown
# their data dirs the way the mysql/mongo images do, so the root-owned bind mounts
# must be handed to them explicitly or the containers crash on permission denied.
chown 65534:65534 "$MOUNT/prometheus"
chown 472:472 "$MOUNT/grafana"
chown 999:999 "$MOUNT/redis"

# ── App directory + compose stack ───────────────────────────────────────
mkdir -p /opt/shophub
cd /opt/shophub

# docker-compose.cloud.yml is embedded at apply time via templatefile(), so the
# instance needs no git/network access to this repo. (nginx.conf and the built
# frontend are baked into the shophub-frontend image by CI, not written here.)
cat > docker-compose.cloud.yml <<'COMPOSE_EOF'
${docker_compose_content}
COMPOSE_EOF

# Secrets go in .env (generated per-instance, never committed), which compose
# reads automatically for the $${VAR} substitutions in the compose file. The
# heredoc delimiter is quoted so bash doesn't re-expand values — Terraform's
# $${...} substitution already ran at apply time, and a literal $ or backtick in
# a generated password must not be mangled.
cat > .env <<'ENV_EOF'
APP_IMAGE=${app_image}
MYSQL_ROOT_PASSWORD=${mysql_root_password}
JWT_SECRET=${jwt_secret}
GRAFANA_ADMIN_PASSWORD=${grafana_admin_password}
DATA_DIR=/data
ENV_EOF
chmod 600 .env

# ── Optional GHCR login ──────────────────────────────────────────────────
# Only needed for a private package; prefer making it public.
if [ -n "${ghcr_token}" ]; then
  echo "${ghcr_token}" | docker login ghcr.io -u "${ghcr_username}" --password-stdin
fi

# ── Start the stack ─────────────────────────────────────────────────────
docker compose -f docker-compose.cloud.yml --env-file .env pull
docker compose -f docker-compose.cloud.yml --env-file .env up -d
