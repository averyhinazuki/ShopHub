# ShopHub Study Notes — Index

Audience: personal study. Written as ShopHub is worked through unit by unit.
Plan: `C:\Users\hinazuki\.claude\plans\what-do-i-do-virtual-goblet.md`

Every topic file uses the same skeleton, so you can skim to the part you need:

1. **What problem it solves** — generic, no ShopHub
2. **How it works** — the mechanism
3. **Where it is in ShopHub** — with `file:line`
4. **Gotchas** — the things that bite
5. **Questions I should be able to answer** — self-check, cold

---

## Lookup table

| # | File | Covers | Status |
|---|------|--------|--------|
| 01 | `01-http-and-nginx.md` | HTTP anatomy, reverse proxy, Docker DNS, why `app` has no published port | **done** |
| 02 | `02-spring-security-jwt.md` | Servlet filter chain, JWT structure, `SecurityContext`, who returns the 401 | **done** |
| 03 | `03-async-checkout.md` | 202 Accepted, polling, why a thread-local can't cross threads | **done** |
| 04a | `04a-database-fundamentals.md` | Full scans & indexes, constraints, transactions, concurrency — **assumes only basic SQL** | **done** |
| 04 | `04-jpa-and-transactions.md` | ORM, persistence context, lazy loading, `@Transactional`, Spring proxies | **done** |
| 05 | `05-redis-caching.md` | Cache-aside, TTL, delayed double-deletion | **done** (absorbs `TECH_OVERVIEW.txt` §1a–1b) |
| 06 | `06-distributed-locks.md` | Race conditions, Redisson, wait vs lease time, reentrancy | **done** (absorbs `TECH_OVERVIEW.txt` §1c) |
| 07 | `07-inventory-concurrency.md` | Atomic conditional UPDATE, compensation, sagas | **done** |
| 08 | `08-kafka.md` | The log, consumer groups, ordering, retry + DLT, idempotency | **done** (absorbs `TECH_OVERVIEW.txt` §2) |
| 09 | `09-order-lifecycle.md` | State machine, `payIfPending` vs expiry scheduler, when a lock *isn't* needed | **done** |
| 10 | `10-mongodb-audit.md` | Document modelling, polyglot persistence, best-effort writes | **done** (absorbs `TECH_OVERVIEW.txt` §3) |
| 11 | `11-docker-and-compose.md` | Images vs containers, volumes, health checks, memory, `user_data.sh.tpl` | **done** |
| 12 | `12-cicd.md` | paths-filter → GHCR → OIDC → SSM, build caching, deploy gating | **done** |
| 13 | `13-terraform-aws.md` | IaC, state, `resource` vs `data`, two roots, AZ derivation, replacement | **done** |
| 14 | `14-observability.md` | Metric types, histograms, scraping, PromQL, percentiles, `noDataState` | **done** |

## Cross-cutting

| File | Purpose |
|------|---------|
| `90-findings.md` | Running defect list. **All 22 original findings fixed (Unit 16, 2026-08-15);** F23–F25 opened during that pass and are still open. |
| `91-drill-questions.md` | Q&A bank + the **final consolidation self-check (PASSED, 2026-08-04)**. Reread the three "second pass" items at the top. |

---

## Unit 16 — the fix-up pass (done, 2026-08-15)

All 22 findings worked in seven phases, one branch and PR per phase, one commit per finding.
Each finding's write-up in `90-findings.md` carries a **Fixed in `<sha>`** note.

**Four corrections the pass made to the findings themselves** — these are the interesting part,
because in each case the original write-up was confident and wrong:

1. **F16/F15 needed `spring.data.mongodb.auto-index-creation: true`.** Spring Boot 3 defaults it to
   `false`, so `@Indexed(expireAfterSeconds=…)` creates nothing. The fix would have been a silent
   no-op — retention that looks configured and deletes nothing.
2. **F17's "separate logical database" option cannot work** — `maxmemory`/`maxmemory-policy` are
   server-wide, not per-DB.
3. **F17 never considered `volatile-ttl`**, which evicts shortest-TTL-first. It ruled out
   `volatile-lru` correctly and stopped there; `volatile-ttl` was a one-word fix sitting right next
   to the option it rejected.
4. **F1's "not yet reproduced by a test"** is now reproduced, and reproducing it is what confirmed
   the mechanism was the *consumer's* status write rather than anything in `processCheckout`.

**Deliberately left undone, each with a reason recorded in the relevant finding:** the transactional
outbox for durable stock compensation (F2/F13's real fix — the counters only make it visible), the
`orders.user_id` FK, `redis-exporter` for eviction metrics, automatic deploy rollback, and the alert
contact point.

**Verification honesty:** Docker was unavailable and AWS was destroyed throughout, so everything in
phases 3 (partly), 5 and 6 landed **without live validation**. Each PR says so explicitly, and
`90-findings.md` records what to check once there is a running box. The lesson from PR #9 — that a
rule's resting state can only be learned by watching real state — is the reason to treat those
phases as unproven rather than done.

---

## If you're looking for…

- **"how does a request actually reach the Java code"** → 01, then 02
- **"why is checkout asynchronous"** → 03, then 08
- **"why does this method call itself through a `self` field"** → 04
- **"how is overselling prevented"** → 07, with 06 as prerequisite
- **"what happens on a duplicate Kafka message"** → 08
- **"what runs on the EC2 box and how did it get there"** → 11, then 12
- **"what breaks if I run `terraform apply`"** → 13
- **"why did that alert fire"** → 14
