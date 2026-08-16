# 11 — Docker and the running stack

Unit 11, opening Block C. No prerequisites beyond `01` (published ports and the container network).

---

# Concept 1 — Images, layers, containers ✅

## An image is a stack of read-only layers

Not a disk image or VM snapshot — **filesystem layers plus metadata** (entrypoint, env, exposed
ports). Each Dockerfile instruction that touches the filesystem produces a layer; later layers
shadow earlier ones.

A **container** is that image plus a thin **writable layer**, plus a running process. Delete the
container and the writable layer goes with it. The image is the artifact; the container is one
execution of it.

## A container is a process, not a VM

| | Virtual machine | Container |
|---|---|---|
| Runs | a full guest OS with its **own kernel** | a **process on the host kernel** |
| Isolation | hypervisor emulating hardware | **namespaces** (what it sees) + **cgroups** (what it uses) |
| Boot | seconds to minutes | milliseconds |
| Overhead | GBs, a whole OS | just the process |

A container has no kernel. A syscall goes **straight to the host kernel**; namespaces only restrict
what it can see.

## Which is exactly why this machine can't run Docker

Containers share the host kernel, so **a Linux container needs a Linux kernel.** Windows has none,
so Docker Desktop runs a lightweight Linux VM (WSL2/Hyper-V) purely to supply one — and that VM
needs Windows' **"Virtual Machine Platform"** feature, which is off here.

Not a Docker problem and not fixable in Docker. Also why the notes correctly found CPU
virtualization *enabled* — the CPU can do it; Windows isn't providing the platform.

## The Dockerfile as layers

```dockerfile
FROM maven:3.9-eclipse-temurin-21 AS build     # stage 1: Maven + full JDK
COPY pom.xml .                                  # layer A
RUN mvn dependency:go-offline -q                # layer B  ← slow, cached
COPY src ./src                                  # layer C
RUN mvn package -DskipTests -q                  # layer D

FROM eclipse-temurin:21-jre                     # stage 2: JRE only, fresh start
COPY --from=build /app/target/shop-hub-*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Layer caching.** `pom.xml` is copied *before* `src` deliberately: dependencies change rarely, code
constantly. Docker invalidates a changed layer **and every layer after it** — so with `COPY src`
first, one edited Java file would also invalidate the Maven download. It's the cascade that costs.

**Multi-stage build.** Stage 1 carries Maven, a full JDK, the source tree and `~/.m2`. Stage 2
starts clean and lifts out only the jar. Beyond size, the final image has **no compiler** (an
attacker with code execution can't build a payload), **no source** (not sitting on a production box),
and **far fewer OS packages** — each one being a CVE you'd otherwise triage. `Dockerfile.frontend`
does the same with Node.

---

# Concept 2 — What survives ✅

The container filesystem is disposable. Anything that must outlive a container lives elsewhere.

| | Named volume | Bind mount |
|---|---|---|
| Syntax | `mysql-data:/var/lib/mysql` | `/data/mysql:/var/lib/mysql` |
| Lives | wherever Docker keeps it | **a path you choose** |
| For | "persist this, don't care where" | "persist this **exactly here**" |

ShopHub uses **bind mounts** (`:59`, `:103`, `:149`, `:164`) with `DATA_DIR=/data` — the mount point
of the separate EBS volume. Comment at `:56-57`: *"not a named volume, so data survives instance
replacement."* A named volume lives on the instance root disk and dies with it; `/data` is detached
and reattached. (→ `13`)

**Kafka has no volume**, deliberately (`:89-90`): *"MySQL is the source of truth, so losing queued
events on a broker restart is acceptable."* Defensible — a lost event means a checkout that never
happens and the user retries.

**Redis also has no volume**, and that's less obviously fine, because Redis is not only a cache:

| Key | Losing it means |
|---|---|
| `product:{id}:detail` | nothing — rebuilds |
| `refresh:{jti}` | **every user logged out** |
| `checkout:{id}` | in-flight checkouts → `PENDING` forever (**F9**) |
| `kafka:processed:*` | **redelivered messages reprocessed** |

## Gotcha — `allkeys-lru` doesn't know what's precious

`--maxmemory 128mb --maxmemory-policy allkeys-lru` (`:65`) evicts the least-recently-used key of
**any** kind. No restart or outage needed — just memory pressure, which is what a spike produces.
(→ **F17**)

---

# Concept 3 — Startup ordering ✅

Plain `depends_on: [mysql]` waits only for the container to be **started**, not **ready**. MySQL
takes seconds after launch to accept connections.

```yaml
mysql:
  healthcheck:
    test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]   # :52
    interval: 10s      # run every 10s
    timeout: 5s        # a run over 5s counts as failed
    retries: 5         # 5 consecutive failures → unhealthy
```

Docker runs that *inside* the container and tracks `starting` → `healthy`/`unhealthy`, which others
can wait on:

```yaml
app:
  depends_on:
    mysql:   { condition: service_healthy }
    redis:   { condition: service_healthy }
    mongodb: { condition: service_healthy }
    kafka:   { condition: service_started }   # no simple health-check for Kafka
```

**Why Kafka is treated differently — fatal vs retried:**

| | MySQL | Kafka |
|---|---|---|
| At startup | **Flyway runs migrations**; unreachable → context fails → **process dies** | producer/consumer **retry indefinitely** in background |
| Failure is | fatal | self-healing |

> `depends_on` orders **launches**. `condition: service_healthy` orders **readiness**. Use the
> second wherever a failed connection is fatal rather than retried.

(The practical reason it isn't health-gated is the comment itself — no cheap one-liner equivalent
to `mysqladmin ping`.)

---

# Concept 4 — Fitting nine containers in 4 GiB ✅

**Two independent levers.**

**Application-level tuning** — tells the *runtime* how much of its main pool to use, necessary
because these programs size themselves from what they can see (a JVM claims a percentage of
available RAM; WiredTiger defaults to ~50%):

```yaml
mysql:   --innodb-buffer-pool-size=128M    # :50
redis:   --maxmemory 128mb                 # :65
kafka:   KAFKA_HEAP_OPTS: -Xmx256m         # :88
mongodb: --wiredTigerCacheSizeGB 0.25      # :96
app:     JAVA_TOOL_OPTIONS: -Xmx512m       # :33
```

**Container-level limits (cgroups)** — a hard kernel-enforced ceiling. Exceed it, the kernel kills
the process. Present on `node-exporter` (`:129`), `prometheus` (`:144`), `grafana` (`:160`) — **and
nowhere else**.

## Gotcha — an application cap is not a memory limit

`-Xmx512m` bounds the heap, not metaspace, thread stacks, direct buffers, JIT code cache or GC
structures; real RSS is commonly 1.5–2x. MySQL with a 128 MB buffer pool typically resides at
300–500 MB once per-connection buffers (×50 Hikari connections) are counted.

So the three services holding **no state** have hard limits and the five holding **all** of it don't
— backwards from where you want the protection. (→ **F18**)

---

# Concept 5 — How the stack reaches a bare instance ✅

`user_data.sh.tpl` runs **once, as root, on first boot** via cloud-init.

**`set -euxo pipefail`** (`:5`) — `-e` exit on error, `-u` error on undefined vars, `-x` print each
command (so `/var/log/cloud-init-output.log` is a real transcript), `-o pipefail` fail on any pipe
stage. Without `-e` a failed step scrolls past and you get a half-built box that looks fine.

## The volume dance — every step guards a specific failure

**Address by `by-id`, not `/dev/sdf`** (`:35-38`). On Nitro the requested device name **isn't
honoured** and NVMe numbering is attach-order dependent; the volume ID is baked into the NVMe serial,
so `by-id` is the only stable handle.

**Wait, don't race** (`:41-46`) — attachment is a *separate API call* Terraform makes after the
instance exists. 60 attempts × 5s.

**Refuse to continue if it never arrives** (`:48-54`) — otherwise Docker creates `/data` on the
**root filesystem** and the stack comes up looking healthy while writing the database to a disk that
dies with the instance. Silently reintroducing the exact bug the volume prevents.

**The most important line in the file** (`:56-64`):

```bash
FSTYPE=$(blkid -p -s TYPE -o value "$DEVICE" 2>/dev/null || true)
if [ -z "$FSTYPE" ]; then  mkfs -t ext4 -L shophub-data "$DEVICE"   # first boot only
else                       echo "Existing $FSTYPE filesystem — preserving it"
fi
```

An unconditional `mkfs` would **wipe the database on every instance replacement**. This conditional
is what makes `terraform apply` routine rather than a data-loss event.

**`nofail` in fstab** (`:68-71`) — a missing disk otherwise drops systemd into emergency mode and the
box is unreachable over SSH.

**Explicit chown** (`:78-82`) — Prometheus (uid 65534) and Grafana (uid 472) don't self-chown their
data dirs the way mysql/mongo images do; root-owned bind mounts crash them on permission denied.

## The compose file travels inside user_data

```bash
cat > docker-compose.cloud.yml <<'COMPOSE_EOF'
${docker_compose_content}
COMPOSE_EOF
```

`templatefile()` embeds it at apply time, so **the instance needs no git access**. That single fact
explains the deployment rule:

> **CI ships images, not the compose file.**

The compose file is part of the boot script, so changing it means changing user_data → a **new
instance** → `terraform apply`. An image change is just `docker compose pull`, which SSM triggers on
the running box. Two change paths, for a structural reason. It's also why the ~12.1 KB against EC2's
**16 KB user_data limit** matters — the script and compose file are the same payload.

## Secrets

`.env` written per instance, `chmod 600`, never committed; compose reads it automatically. The
**quoted** heredoc delimiter (`'ENV_EOF'`) stops bash re-expanding values — a generated password
containing `$` or a backtick would otherwise be mangled.

## The model

**Immutable infrastructure.** The instance is disposable and reconstructible from code; only the EBS
volume persists. You don't SSH in to fix things — you change the code and replace the box.

## Questions I should be able to answer

- What is an image, what is a container, and what's the difference?
- Why is a container not a VM — and why does that make Windows need Virtual Machine Platform?
- Why copy `pom.xml` before `src`? What's the cascade?
- Beyond size, what does a multi-stage build buy?
- Named volume vs bind mount — and why does ShopHub use bind mounts?
- Which services have no volume, and which of those is a genuine risk?
- What does `allkeys-lru` evict, and why is that more than a cache concern?
- `depends_on` vs `condition: service_healthy` — state the rule.
- Why is Kafka not health-gated when MySQL is?
- Why isn't `-Xmx512m` a memory limit?
- Name three specific failures the volume-mount block in `user_data.sh.tpl` guards against.
- Why does CI ship images but not the compose file?
