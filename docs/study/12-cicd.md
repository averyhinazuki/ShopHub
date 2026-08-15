# 12 — CI/CD

Unit 12. Prerequisite: `11` (images, layer caching, and why the compose file ships via
`terraform apply` rather than CI).

Source: `.github/workflows/ci.yml`.

---

# Concept 1 — The pipeline, and knowing what changed ✅

**CI** — every push is built and tested automatically, so breakage surfaces in minutes.
**CD** — a passing build ships automatically.

```
changes ──→ test ──→ publish-backend    ─┐
        ├─────────→ publish-frontend    ─┤
        ├─────────→ publish-prometheus  ─┼──→ deploy
        └─────────→ publish-grafana     ─┘
```

## Change detection

`dorny/paths-filter` classifies the diff into three booleans (`:32-50`):

```yaml
shared: &shared
  - '.dockerignore'
backend:    [*shared, 'src/**', 'pom.xml', 'Dockerfile']
frontend:   [*shared, 'frontend/**', 'Dockerfile.frontend', 'infra/nginx/**']
monitoring: ['infra/monitoring/**']
```

`&shared` is a YAML anchor: `.dockerignore` belongs to **both** image filters because both builds
use `context: .`, so changing what's sent to the build changes both images.

**What's excluded is the point** (`:33-36`): this workflow file and `docker-compose.cloud.yml` are
*not* filter paths — *"neither is an image input (the compose file ships via terraform apply, not
CI)."* That's `11`'s structural rule, enforced here.

Trace it: change **only** the compose file → no filter matches → every publish skips → deploy's
`contains(needs.*.result, 'success')` is false → **nothing happens.** Correct, because that change
needs `terraform apply`.

## The `workflow_dispatch` trap

```yaml
backend: ${{ github.event_name == 'workflow_dispatch' && 'true' || steps.filter.outputs.backend }}
```

A manual run has **no diff**. paths-filter would compare main against main, find nothing changed,
skip every build — **and report green.** A success that built nothing. The ternary forces all three
true on manual dispatch.

And `workflow_dispatch` exists at all (`:8-10`) because editing the workflow matches no path filter,
so there'd otherwise be no way to trigger a build after changing it.

> The failure mode being defended against isn't a red build. It's a **green build that did nothing**
> — far more dangerous, because nobody investigates green.

---

# Concept 2 — Build once, tag twice, cache across throwaway runners ✅

## Service containers

The `test` job runs MySQL, MongoDB, Redis and Kafka as sidecars with health checks (`:57-104`),
mirroring `docker-compose.yml`. Which is why `ShopHubApplicationTests.contextLoads` passes in CI but
fails locally — CI has the infrastructure.

## Two tags

```yaml
tags: |
  ghcr.io/.../shophub:latest
  ghcr.io/.../shophub:${{ github.sha }}
```

`:latest` is the moving pointer compose pulls; `:{sha}` is an **immutable** reference for rollback
and forensics. **Though nothing consumes the SHA tag** — all four compose services and
`variables.tf:53` reference `:latest`. It's a record you could roll back to by hand, not a mechanism.

## Caching on a machine that gets destroyed

Every run gets a **fresh runner** with no local layer cache, so every build would redownload Maven.

```yaml
- uses: docker/setup-buildx-action@v3
cache-from: type=gha,scope=backend
cache-to:   type=gha,scope=backend,mode=max
```

- **`type=gha`** — cache stored in GitHub Actions' cache service, surviving between runs
- **`mode=max`** — caches **intermediate stages**. Without it (`mode=min`, the default) only the
  *final* stage's layers export. The final stage here is `FROM jre` + `COPY jar` — three trivial
  layers — while all the expensive work lives in **stage 1, which is discarded**. So without
  `mode=max`, layer caching buys **nothing in CI**.
- **`scope=backend`/`scope=frontend`** — isolates the caches so they don't evict each other
- **`setup-buildx-action` is required** because gha cache export needs BuildKit's
  `docker-container` driver; the runner's default docker driver can't export (`:136-137`)

---

# Concept 3 — OIDC: deploying with no stored credentials ✅

## The old way and why it's bad

`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` as repo secrets: **long-lived credentials sitting in
GitHub**, rotated manually (so never), and a leak — compromised action, fork, logged env var — means
account access until someone notices.

## The OIDC way

```yaml
permissions:
  id-token: write        # required to MINT the token
  contents: read

- uses: aws-actions/configure-aws-credentials@v4
  with:
    role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
```

1. GitHub mints a short-lived **signed JWT** describing this run — repo, ref, actor, workflow
2. The action presents it to AWS STS
3. AWS validates the signature against GitHub's OIDC provider and checks claims against the role's
   **trust policy**
4. AWS returns ~1h credentials scoped to `SendCommand` on instances tagged `Name=shophub`

**There is no secret to leak.** Nothing long-lived exists.

The trust policy pins `repo:averyhinazuki/ShopHub:ref:refs/heads/main`, so a fork PR — whose token
carries a different `sub` claim — is refused. (Casing matters; `StringEquals` is case-sensitive.)

**The ARN is not a credential.** Knowing it gains an attacker nothing, because access is gated by
the signed claim, not the role's name. The comment says so: it's a secret *"only to keep the account
ID out of a public repo."* Obfuscation, not security.

**Gotcha:** naming any permission **resets all others**, which is why `contents: read` is restated
alongside `id-token: write` (`:269-270`).

---

# Concept 4 — SSM deploy, and a subtle condition ✅

## Why not SSH

| | SSH | SSM |
|---|---|---|
| Credentials | a private key in GitHub | none — IAM |
| Firewall | port 22 open to GitHub's (changing) IP ranges | **nothing inbound at all** |
| Addressing | needs the host IP | finds it by **tag** |
| Audit | your own logs | CloudTrail, per command |

The SSM agent **polls outbound**; AWS never connects in. And `tag:Name=shophub` (`:293-295`) means
the pipeline survives instance replacement with no config change — necessary given `11`'s immutable
infrastructure model.

## The deploy `if:`

```yaml
needs: [ publish-backend, publish-frontend, publish-prometheus, publish-grafana ]
if: !cancelled() && github.ref == 'refs/heads/main'
    && contains(needs.*.result, 'success') && !contains(needs.*.result, 'failure')
```

The problem: **GitHub skips a job whose dependency was skipped.** On a frontend-only change three of
four publishes skip, so deploy would skip too and nothing would ship.

- `!cancelled()` overrides that default skip
- `contains(..., 'success')` — at least one image was published, so there's something to deploy
- `!contains(..., 'failure')` — nothing broke

And `docker compose up -d` only recreates containers whose image changed, so a frontend-only deploy
leaves the app untouched (`:287-288`).

## The last line makes it honest

```bash
aws ssm get-command-invocation ... --output table   # surface the box's stdout/stderr
[ "$STATUS" = "Success" ]                            # ← determines the job's exit code
```

Without that test the script would end on a successful `aws` call and go **green even when the
deploy failed on the box**.

There's also a graceful no-instance path (`:297-300`): exit 0 with a note that images are published
and the next `terraform apply` will pick them up.

## Gotcha — `Success` means the command ran, not that the app works

Only mysql/redis/mongodb have healthchecks; **`app` has none**, so `docker compose up -d` returns as
soon as the container launches. A bad migration or config makes Spring fail at startup and the
container crash-loop — while compose already exited 0 and SSM reported `Success`. The pipeline goes
green on a deploy that took the site down. (→ **F19**)

## Questions I should be able to answer

- What does the `changes` job buy, and which two files are deliberately excluded from its filters?
- Why would a manual run report green while building nothing, and how is that prevented?
- Why is `.dockerignore` in both image filters?
- What breaks if you drop `mode=max`? Why is it worse for a multi-stage build?
- Why is `setup-buildx-action` needed at all?
- Walk through the four steps of the OIDC exchange. What is there to leak?
- Why is publishing the role ARN safe?
- Three concrete advantages of SSM over SSH here.
- Why does the deploy job need `!cancelled()`?
- Why does the script end with `[ "$STATUS" = "Success" ]`?
- What kind of broken deploy still reports green today?
