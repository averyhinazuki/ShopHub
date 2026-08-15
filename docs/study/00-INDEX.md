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
| `90-findings.md` | Running defect list. F1–F3 already logged. |
| `91-drill-questions.md` | Q&A bank + the **final consolidation self-check (PASSED, 2026-08-04)**. Reread the three "second pass" items at the top. |

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
