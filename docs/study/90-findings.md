# Findings — running defect list

Real problems found in ShopHub while studying it. **Not fixed as they're found** — each is
taught as a case study in the relevant unit, then worked in Unit 16.

Status values: `open` / `fixed` / `known-deliberate`.

## Index

Bodies below are not in numeric order — use this table.

Unit 16 (the fix-up pass) is working these in phases; `Status` tracks it.

| # | Finding | Severity | Status | Unit |
|---|---|---|---|---|
| F1 | DLT reports FAILED for a checkout that created a real order | high | **fixed** | 8 |
| F2 | `OrderExpiryScheduler` leaks stock permanently on lock timeout | high | known-deliberate | 9 |
| F3 | Second deletions fall behind — 500ms sleep caps the pool at ~16/sec *(revised: pools are NOT shared)* | medium | open | 5 |
| F4 | Duplicate username returns 500 instead of 409 | low | **fixed** | 1 |
| F5 | `UserActionLogFilter` ordering comment states a false rationale | doc | **fixed** | 2 |
| F6 | `JwtUtil` javadoc says 15m, config says 5m | doc | **fixed** | 2 |
| F7 | `resolveUserId()` does a DB query per request; 500 for deleted user | medium | open | 2 |
| F8 | **Frontend never polls checkout status — async flow half-wired** | **highest** | open | 3 |
| F9 | Absent checkout key returns PENDING → a correct client polls forever | high | **fixed** | 3 |
| F11 | `product:{id}:stock` is written and deleted but never read | trivial | **fixed** | 5 |
| F15 | No Mongo indexes - any audit-trail read is a full collection scan | medium | open | 10 |
| F20 | Empty `terraform.tfstate` committed at repo root (no secrets) | trivial | **fixed** | 13 |
| F22 | No domain metrics - every finding above would be invisible in prod | medium | open | 14 |
| F21 | AMI re-resolves each plan, so any apply can replace the instance | known-deliberate | open | 13 |
| F19 | Deploy goes green even if the app crash-loops - no smoke test, no app healthcheck | medium | open | 12 |
| F18 | `mem_limit` on the 3 stateless services, none of the 5 stateful ones | medium | open | 11 |
| F17 | **Redis `allkeys-lru` can evict dedup keys, refresh tokens, checkout status** | **high** | open | 11 |
| F16 | **No Mongo TTL - unbounded growth on the volume MySQL shares** | **high** | open | 10 |
| F14 | 3 partitions but concurrency=1 - async drain runs at 1/3 rate | medium | open | 8 |
| F13 | Checkout compensation failure is logged, never retried or alerted | high | open | 7 |
| F12 | Redis outage makes product pages 500 instead of degrading | medium | **fixed** | 5 |
| F10 | `orders.user_id` has no index and no FK constraint | medium | open | 4 |
| F23 | Bare `RuntimeException` still thrown at four service sites → 500s | low-med | open | 16 |
| F24 | The `checkout:{id}` record is hand-rolled in three classes | low | open | 16 |


---

## F1 — DLT can report FAILED for a checkout that created a real order — `fixed`

**Fixed** in `8f091bf` (Unit 16, phase 2). `writeStatus` now swallows any exception, not just
`JsonProcessingException` — the order is committed by the time it runs, so failing the message can
only cause harm. The DLT's status *read* is wrapped separately and **returns without writing** on
failure: if the current status can't be read, a SUCCESS may be sitting there unread, and writing
FAILED blind is the exact mistake the SUCCESS-guard exists to prevent.

Now reproduced by a test (`handleCheckoutRequested_statusWriteFailsAfterOrderCommitted_doesNotRethrow`),
closing the write-up's "Not yet reproduced by a test".

**Where:** `src/main/java/com/example/shophub/kafka/consumer/CheckoutRequestedConsumer.java`
**Taught in:** Unit 8 (Kafka)

The consumer handles "a retry arrives after the cart was already cleared" on the **success
path only** — that's what the `safeMarkProcessed(key)` call after `writeStatus` is for.

### Mechanism corrected in Unit 7

The original write-up said the trigger was *"`processCheckout` throws after `persistOrder`
committed."* **That cannot happen.** `persistOrder` is `@Transactional`, so a throw inside it
rolls its own work back, and it is the last statement in `processCheckout`'s `try`. There is no
window there.

**The real trigger is in the consumer, one level up:** `writeStatus` failing *after*
`processCheckout` returned successfully.

1. `processCheckout` succeeds completely — order row written, items written, cart cleared, stock
   deducted. All committed.
2. `writeStatus` (`CheckoutRequestedConsumer:70-74`) tries to write `SUCCESS` to Redis and
   **throws** — Redis briefly unreachable. It catches only `JsonProcessingException` (`:141-143`),
   so `RedisConnectionFailureException` propagates. (Same root cause family as **F12**.)
3. `safeMarkProcessed` at `:77` never runs, so the dedup key is never set.
4. The generic `catch (Exception e)` at `:91-96` rethrows → `@RetryableTopic` redelivers.
5. On redelivery `isAlreadyProcessed` is false, so it proceeds — and `loadCartSnapshot` finds the
   cart **already empty** → `IllegalArgumentException("Cart is empty")`.
6. Not a `SoldOutException`, so it rethrows. Retries exhaust.
7. `@DltHandler` reads `checkout:{id}`. Status is still **`PENDING`** — `writeStatus` never
   succeeded — so the SUCCESS-guard at `:107-115` doesn't trip.
8. Writes **`FAILED`**.

**Result:** the user is told their checkout failed while a real, payable order exists.

**Also corrected:** the original claimed "the stock was returned to the pool, order and inventory
now disagree." Wrong — on the retry, `loadCartSnapshot` throws *before* any deduction, so
`deducted` is empty and the compensation loop does nothing. Order and inventory **agree**; both
reflect a genuine sale. The damage is purely that the customer is misinformed — and, per **F8**,
they'll likely retry and create a second real order.

~~Not yet reproduced by a test.~~ Reproduced and guarded — see the fix note at the top.

---

## F2 — `OrderExpiryScheduler` loses stock permanently — `known-deliberate`, but undetectable

**Where:** `scheduler/OrderExpiryScheduler.java:126-136`, `:152-155`
**Taught in:** Unit 9

### Reframed in Unit 9 — this is documented as intentional

The original write-up treated this as an oversight. **It isn't.** The code says so at `:126-127`:

> *"If the lock can't be acquired the item is skipped and its stock is not restored — a retry
> queue would close this gap; left out here by design."*

Someone identified the gap, judged a retry queue out of scope, and wrote it down. That is a
legitimate engineering decision, and "we knowingly accepted this" is a far better position than
"we missed it." The finding stands on a narrower basis, below.

### The mechanism

Two steps, only the first guaranteed:

1. `cancelIfPending(orderId)` (`:87`) — conditional UPDATE, **commits immediately**
2. `restoreStockForItem(...)` (`:129`) — two ways to abandon it:
   - `tryLock` times out → `log.warn` and `return` (`:133-137`)
   - `restoreStock` throws → catch, unlock, `log.error` (`:152-155`)

Resulting state: order **`CANCELLED`** and committed; stock **not restored**. Order and inventory
actively disagree — the order claims those units were released, inventory still has them deducted.
Nothing reconciles it.

**It is per item.** The loop at `:102-107` restores each line separately under its own lock, so an
order with three items where only the second times out yields a **partially restored
cancellation** — harder to spot than an all-or-nothing failure, with no record of which case
occurred.

### The actual critique: accepted without detection

A knowingly-accepted gap that produces **no metric, no alert, and no reconciliation** is a gap you
can never learn you have hit. The only trace is a `log.warn` nobody reads. You'd discover it when
someone notices a product reading sold-out while sitting in the warehouse — by which point you
cannot reconstruct how much was lost or when.

> Accepting a known gap is fine. Accepting it *without detection* also gives up the ability to
> find out whether it mattered.

**Cheapest fix is not the retry queue** — it's a counter metric on that warn path plus an alert
rule, so "this fired 40 times last night" becomes visible. A couple of lines against a design
decision that is otherwise defensible. The durable fix is the same transactional-outbox shape as
**F13**, its twin on the checkout side.

---

## F22 - The dashboard measures infrastructure only; every logged finding would be invisible - `open` (medium)

**Where:** `infra/monitoring/dashboards/shophub.json` (15 panels), `provisioning/alerting/rules.yml` (3 rules)
**Taught in:** Unit 14

Infrastructure coverage is genuinely thorough - HTTP rate/latency/errors, JVM heap, GC, threads,
CPU, load, HikariCP pool depth and acquire time, Kafka consumer lag, host disk/CPU/memory, scrape
health. Three alerts: app scrape down, p99 > 1s, `/data` < 20% free.

**There are no domain metrics at all.** Nothing counts checkout outcomes, DLT depth, or compensation
failures. So every serious finding in this study would be invisible in production while the
dashboard stayed green:

| Finding | Would be caught by | Exists? |
|---|---|---|
| **F1** - checkouts reaching the DLT | DLT message count / lag on `*-dlt` topics | ❌ |
| **F2**, **F13** - stock restorations abandoned | counter on the warn/error path + alert | ❌ |
| **F19** - deploy crash-looping the app | app healthcheck + container restart count | ❌ |
| **F17** - Redis evicting dedup keys | `redis_evicted_keys_total` | ❌ |
| **F16** - Mongo growth filling `/data` | `/data` alert catches the *symptom* only | partial |

**Highest-value additions, in order:**
1. **DLT depth** - any message in a `*-dlt` topic is by definition something that exhausted retries
   and nobody has looked at. Should alert at > 0.
2. **Checkout outcome counters** - `SUCCESS` / `FAILED(sold out)` / `FAILED(exhausted)`. A shift in
   the ratio is the earliest possible signal of most of these bugs.
3. **Compensation-failure counter** - already prescribed in F2 and F13; it's the same metric.
4. **Redis evictions** - `redis_evicted_keys_total` non-zero means F17 is live.

Micrometer makes all of these a few lines (`Counter.builder(...).register(meterRegistry)`), and they
would flow to the existing dashboard with no new infrastructure.

**Related:** the alerts also have **no delivery path** (`rules.yml:4-8`) - no contact point is
provisioned, so they fire onto a default policy with no SMTP and silently no-op. Documented as
deliberate backlog and the reason is legitimate (needs a secret and a destination). But combined
with the above it means the observability stack currently tells you nothing unless someone opens
Grafana and looks.

---

## F21 - Any `terraform apply` can silently replace the instance, because the AMI re-resolves - `known-deliberate`

**Where:** `infra/terraform/main.tf:52-54` (data source), `:65-66` (consumed by the instance)
**Taught in:** Unit 13

`data.aws_ssm_parameter.al2023` re-resolves on **every plan**, and Amazon publishes new AL2023
images regularly. `ami` is a replace-forcing attribute, so any future `apply` - for any unrelated
reason - can destroy and recreate the instance simply because a newer image exists.

The result is an **unplanned OS upgrade plus ~2 minutes of downtime while you were fixing something
else**, with the change appearing in a plan you were reading for a different purpose.

The code acknowledges it (`:50-51`): *"AMI changes replace the instance - cheap now that the
databases live on a volume outside this state."* And it *is* cheap - Concepts 2/3 of Unit 13 are
what make it cheap. But **cheap is not the same as intended.**

**Fix if you want deliberate OS upgrades:**

```hcl
lifecycle { ignore_changes = [ami] }
```

Then the image is pinned to whatever the instance was built with, and upgrading becomes an explicit
act (`terraform taint` / `-replace`) rather than a side effect.

**Related history worth keeping:** the AMI was previously selected by name-glob, and
`al2023-ami-*-x86_64` also matched `al2023-ami-minimal-*`. The minimal image published minutes later,
`most_recent = true` picked it, and the minimal AMI **lacks the SSM agent** - breaking every CI
deploy with `InvalidInstanceId`. `most_recent` over a glob means AWS's publishing schedule decides
your infrastructure. That is not a pin, it is a subscription.

---

## F20 - An empty `terraform.tfstate` is committed at the repo root - `fixed` (trivial)

**Fixed** in `f063a3c` (Unit 16, phase 1). `git rm --cached`; the file stays on disk, now
covered by `.gitignore:89`.

**Where:** repo root `terraform.tfstate`, added in commit `4798ba3`
**Taught in:** Unit 13

**No secrets are exposed.** The file is 181 bytes, `serial 1`, **0 resources** - created by running
`terraform` from the repo root by accident. Both real states
(`infra/terraform/`, `infra/terraform/persistent/`) are correctly ignored and have never been
committed (verified against full git history).

The trap is the classic one: `.gitignore:84` now has `*.tfstate`, and `:82-83` even documents the
guard - but **gitignore does not untrack a file that was already committed**, so the empty state
remains in the repo.

Worth cleaning because state files are the single most sensitive artifact in a Terraform repo -
they contain rendered `user_data`, which for this stack embeds `mysql_root_password`, `jwt_secret`
and `grafana_admin_password` in plaintext. A committed *empty* state is harmless; the habit is not.

**Fix:** `git rm --cached terraform.tfstate && git commit`. The `.gitignore` rule already prevents
recurrence.

---

## F19 - The deploy verifies the command ran, not that the app works - `open` (medium)

**Where:** `.github/workflows/ci.yml:289-326`; `docker-compose.cloud.yml` app service has no healthcheck
**Taught in:** Unit 12

The deploy job is careful about the things it checks - it polls SSM to a terminal state, surfaces
the box's stdout/stderr, and ends on `[ "$STATUS" = "Success" ]` so a failed command fails the job.
But `Success` means **the SSM command ran**, not that the deployment worked.

`docker compose up -d` returns as soon as containers are **started**. Of the nine services only
mysql, redis and mongodb define a `healthcheck`; **the `app` service has none**, so compose does not
wait for the application to become ready and returns immediately after launch.

**The failure that gets through:** ship a bad Flyway migration or a broken config value. The app
container starts, Spring fails during startup, the container crash-loops under
`restart: unless-stopped`. Meanwhile `docker compose up -d` already exited 0, SSM reports `Success`,
and **the pipeline goes green on a deploy that took the site down.**

**Fixes, cheapest first:**
1. **Post-deploy smoke test** in the same SSM command - append
   `&& sleep 15 && curl -fsS http://localhost/api/products > /dev/null`. The `-f` makes curl exit
   non-zero on an HTTP error, so a broken deploy fails the pipeline.
2. **Give `app` a healthcheck** in the compose file (it already exposes `/actuator/health`, and
   `SecurityConfig:55` permits it). Then `depends_on` conditions and `docker compose ps` both become
   meaningful, and nginx could gate on it.
3. **Automatic rollback** - the SHA-tagged images already exist in GHCR
   (`ci.yml:155`), but nothing consumes them: `docker-compose.cloud.yml` references `:latest` in all
   four services and `variables.tf:53` defaults to `:latest`. So rollback today means manually
   retagging or editing compose on the box. The immutable tags are a record, not a mechanism.

---

## F18 - `mem_limit` is set on the three stateless services and none of the five stateful ones - `open` (medium)

**Where:** `docker-compose.cloud.yml` - `mem_limit` appears only at `:129`, `:144`, `:160`
**Taught in:** Unit 11

The file header claims *"Memory is capped on every service, sized for a 4 GiB instance."* It isn't.
`mem_limit` is set on **node-exporter (128m), prometheus (256m), grafana (256m)** and on nothing
else. The app, MySQL, Redis, Kafka, MongoDB and nginx have **no container-level limit**.

They have *application-level* tuning instead - `--innodb-buffer-pool-size=128M`,
`--maxmemory 128mb`, `-Xmx512m`, `--wiredTigerCacheSizeGB 0.25` - which is necessary but is **not
the same thing**. An application cap bounds one pool, not total process memory. A JVM with
`-Xmx512m` also consumes metaspace, thread stacks, direct byte buffers, JIT code cache and GC
structures; real RSS is commonly 1.5-2x the heap. MySQL with a 128 MB buffer pool typically resides
at 300-500 MB once per-connection buffers (x50 Hikari connections), the log buffer and table cache
are counted.

**Consequence when the box runs short:**

| | With `mem_limit` | Without |
|---|---|---|
| Who dies | that container, deterministically | host OOM killer picks by heuristic |
| Which process | the offender | possibly an innocent one - MySQL is a big, attractive target |
| Diagnosis | exit code 137, obvious | a mystery kill in `dmesg` |

So the three services holding **no state** are protected, and the five holding **all** of it are
not. That is backwards from where the protection is wanted.

**Fix:** add `mem_limit` to the remaining services, sized above their application cap with headroom
for non-heap memory (e.g. app 768m against `-Xmx512m`, mysql 512m against a 128M buffer pool), and
verify the total fits 4 GiB. Then correct the header comment.

**Also minor:** the closing comment at `:168-169` says *"both stateful services bind-mount"* - it is
four now. Prometheus (`:149`) and Grafana (`:164`) persist to `/data` too. The comment predates them.

---

## F17 - Redis `allkeys-lru` can evict idempotency keys, refresh tokens and checkout status - `open` (high)

**Where:** `docker-compose.cloud.yml:65` - `command: --maxmemory 128mb --maxmemory-policy allkeys-lru`
**Taught in:** Unit 11

The comment calls Redis a *"bounded cache with LRU eviction"* - accurate for the cache, and the
policy quietly applies to three other things that are **not** caches:

| Key pattern | Contents | Eviction means |
|---|---|---|
| `product:{id}:detail` | cache | nothing - rebuilds on next read |
| `refresh:{jti}` | refresh tokens | **user logged out mid-session, apparently at random** |
| `checkout:{id}` | checkout status | in-flight checkout to `PENDING` forever (**F9**) |
| `kafka:processed:*` | idempotency guard | **redelivered message reprocessed - second deduction, second order** |

`allkeys-lru` evicts the least-recently-used key of **any** kind once 128 MB fills. Redis has no
idea some of those keys are load-bearing. This needs **no restart and no outage** - just memory
pressure, which is exactly what a traffic spike produces.

The dedup case is the worst: an evicted `kafka:processed:*` makes `isAlreadyProcessed` return false
for an already-completed message, so a redelivery reprocesses it in full.

Note `volatile-lru` would **not** help - all four key types carry TTLs, so it would behave
identically. The real problem is mixing disposable cache with semi-critical state in one 128 MB
budget with no priority between them.

**Options, roughly in order of cost:**
1. **Raise `maxmemory`** so eviction is unlikely - cheapest, doesn't fix the class of problem.
2. **Separate the concerns** - a different Redis logical database or instance for cache vs
   state, with eviction enabled only on the cache one.
3. **Move dedup off Redis** - a unique index in MySQL makes it durable and non-evictable (the same
   fix proposed in `08-kafka.md` for the check-then-act race).

Also note Redis has **no volume** (`:61-70`), so a restart wipes all four key types outright. Losing
the cache is fine; losing the other three is the same damage as above, all at once.

---

## F16 - MongoDB collections have no TTL, so an audit log can kill MySQL - `open` (high)

**Where:** `document/UserActionLog.java`, `document/OrderActivityLog.java` - no `@Indexed(expireAfterSeconds=...)`
**Taught in:** Unit 10

Neither collection has any retention. `user_action_log` gains **one document per authenticated
request**, forever. `order_activity_log` gains 2-3 per order, forever. Nothing deletes anything.

**Why this is worse than "the disk fills up":** both collections live on the **10 GiB `/data` EBS
volume that is bind-mounted for MySQL as well**. Unbounded audit growth therefore consumes the
transactional database's disk. If `/data` fills, **MySQL stops writing and the entire site goes
down** - taken out by a debugging aid nobody reads.

There *is* an alert for the symptom (`/data` < 20% free, `infra/monitoring/provisioning/alerting/rules.yml`)
but nothing addresses the cause, and the alert gives you a disk to clear, not a mechanism.

**Fix:** a TTL index - MongoDB deletes expired documents itself, no job required:

```java
@Indexed(expireAfterSeconds = 2592000)   // 30 days
private LocalDateTime timestamp;
```

Pick retention per collection - `user_action_log` is high-volume and low-value (30 days),
`order_activity_log` is the lifecycle trail you'd want during an investigation (longer).

---

## F15 - Neither Mongo collection has an index, so reading the audit trail is a full scan - `open` (medium)

**Where:** both `document/*.java` (no `@Indexed`/`@CompoundIndex`), both `repository/mongo/*.java`
**Taught in:** Unit 10

Verified by grep: **no indexes anywhere** beyond the automatic `_id`. Both repositories are empty
interfaces and `save()` is the only operation the codebase ever calls - nothing reads these
collections from code today.

That's exactly why it's a latent problem. The first time anyone actually needs the audit trail - an
admin screen, a support request, "what did user 42 do before this happened" - the natural queries
are `by userId`, `by orderId`, `by timestamp range`, and **every one is a full collection scan**.

Unlike MySQL, MongoDB will not warn you: it scans silently, forever. And the collection is largest
exactly when you need it most, during an incident.

**Fix:** index the fields you will query before you need to query them -
`userId`, `timestamp` on `user_action_log`; `orderId`, `event`, `timestamp` on
`order_activity_log`. A compound index on `(userId, timestamp)` covers the common
"what did this user do recently" access pattern in one.

Note the TTL index from **F16** would also serve as a `timestamp` index, so the two fixes overlap.

---

## F14 - Three partitions, one consumer thread: the async drain rate is 1/3 of design - `open` (medium)

**Where:** no `spring.kafka.listener.concurrency` anywhere; `config/KafkaTopicConfig.java:20,28,36`
**Taught in:** Unit 8

All three topics are created with **3 partitions**. But `@KafkaListener` uses
`ConcurrentKafkaListenerContainerFactory`, whose **default concurrency is 1**, and nothing
overrides it - there is no `spring.kafka.listener.concurrency` property and no custom factory bean.

So one consumer thread is assigned all three partitions and drains them **sequentially**:

```
checkout-requested: 3 partitions -> ONE thread, one message at a time
```

The parallelism the topic design provides is **latent, never realised**. Checkout throughput is
bounded by a single thread doing lock acquisition + stock deduction + persistOrder per message -
roughly 3-20 checkouts/sec depending on contention.

**Nothing breaks.** The queue still absorbs the spike, which was the point of async checkout. But
it **drains at a third of the intended rate**, so under a real flash sale the backlog and
time-to-order stretch out - quietly undercutting the design's own stated goal.

**Fix:** `spring.kafka.listener.concurrency: 3` - one line, triples the drain rate using
partitions that already exist. Beyond 3 gains nothing without adding partitions, since partition
count is the parallelism ceiling.

**Caveat before raising it:** more concurrency widens the check-then-act window in
`ConsumerIdempotencyGuard` (see the F-notes in `08-kafka.md`), since concurrent processing of
different partitions becomes real rather than theoretical. Still safe - different partitions hold
different checkoutIds - but worth understanding before turning it up.

---

## F13 - Checkout compensation failure is logged and then forgotten - `open` (high)

**Where:** `service/OrderService.java:163-176` (the catch at `:170-173`)
**Taught in:** Unit 7

The compensation loop restores stock for every already-deducted item when a later step fails.
Each restore is wrapped in its own try/catch that **only logs**:

```java
} catch (Exception compensationEx) {
    log.error("[Checkout][Compensation] FAILED to restore productId={} qty={}: {}", ...);
}
```

If `restoreStock` fails - a dropped MySQL connection, a timeout - that stock is **permanently
lost**. The database then believes fewer units exist than physically do. Nothing self-heals, and
repeated occurrences shrink inventory monotonically: products read "sold out" while sitting on the
shelf.

**Detection today is essentially nil.** The only trace is that log line. No alert, no metric, no
counter - the three provisioned Grafana rules cover scrape health, p99 latency and disk space,
none of which sees this. A binlog replay would show a deduction with no matching restoration, but
that is indistinguishable from a legitimate sale, so it detects nothing by itself.

**This is the twin of F2.** F2 is the expiry scheduler abandoning restoration on a lock timeout;
this is checkout abandoning it on a database failure. One flaw in two places: **stock restoration
is best-effort everywhere.**

**Fixes, in order of strength:**
1. **Durable compensation** - write a "restore owed" row in the same transaction as the deduction
   and drain it from a retry job. The transactional-outbox pattern again (see F1/Unit 3).
2. **Reconciliation job** - periodically assert
   `total_stock == available_stock + units held by PENDING orders`; any drift means a lost restore.
3. **At minimum, alert on the log line** - a counter metric on compensation failure, with a rule.

---

## F12 - A Redis outage turns product browsing into 500s - `fixed` (medium)

**Fixed** in `43a0dbd` (Unit 16, phase 2). `getDetail` treats a connection failure as a miss;
`setDetail`/`deleteCache` log and return. `deleteCache` matters most — it runs *before* the MySQL
write it protects, and the entry carries a 60s TTL, so the worst case is bounded staleness rather
than a failed write. New `ProductCacheServiceTest` covers all three paths.

**Where:** `service/ProductCacheService.java:41-78`
**Taught in:** Unit 5

Cache-aside's defining promise is that the cache is *not* in the data path - MySQL is the truth
and Redis may be wiped or unavailable with no loss of correctness. **This implementation does not
deliver that promise.**

- `getDetail:44-50` catches `JsonProcessingException` only
- `setDetail:66-68` likewise
- `deleteCache:74-78` catches nothing

If Redis is unreachable, `stringRedisTemplate` throws `RedisConnectionFailureException`, which is
uncaught. It propagates out of `ProductService.getProduct` to `GlobalExceptionHandler:51` and
becomes a **500**. So a Redis outage takes out product browsing entirely, rather than merely
making it slower.

**Fix:** wrap the Redis calls so a connection failure degrades instead of throwing - `getDetail`
returns `null` (treated as a miss), `setDetail`/`deleteCache` log and return. One `catch` per
method converts "product pages are down" into "product pages are slower."

Note the blast radius is wider than the cache: Redis also backs Redisson locks, checkout status,
the dedup guard, and refresh tokens. But those are genuine dependencies. The *cache* is the one
piece that is supposed to be optional and isn't.

---

## F11 - `product:{id}:stock` is written and deleted but never read - `fixed` (trivial waste)

**Fixed** in `d2a7381` (Unit 16, phase 1). `getStock`, `STOCK_KEY`, `stockKey` and the two
maintenance lines are gone. **Bonus defect found while fixing:** `ProductService.updateProduct`'s
javadoc claimed ":stock is untouched", but `deleteCache` had been deleting it all along — the
comment was already false before the removal.

**Where:** `service/ProductCacheService.java:33`, `:53-56`, `:64-65`, `:76`
**Taught in:** Unit 5

`getStock` is called from **nowhere** - not main, not tests (verified by grep across `src/`). The
key is written on every cache fill (`:64-65`) and deleted on every eviction (`:76`), so every
cache operation does double the Redis round trips for a key with no reader.

It's also redundant: `availableStock` is already inside the `product:{id}:detail` JSON, which is
where `getProduct` reads it from.

**Fix:** delete `getStock`, `STOCK_KEY`, `stockKey`, and the two lines that maintain it. Halves
the Redis write and delete traffic for the product cache.

---

## F10 — `orders.user_id` has no index and no foreign key constraint — `open` (perf + integrity)

**Where:** `db/migration/V1__init.sql` (orders table), `entity/Order.java:19-20`
**Found in:** Unit 4 (ORM basics)

Every other relationship column in the schema has both an index and an FK constraint —
`products.category_id`, `product_inventory.product_id`, both columns of `cart_items`, both of
`order_items`. The `orders` table declares only `PRIMARY KEY (id)`.

**Speed.** `OrderService.getMyOrders` (`:303`) runs `findByUserId` →
`SELECT * FROM orders WHERE user_id = ?` on every order-history page load. With no index that is
a **full table scan of every order ever placed**. Invisible at 200 rows; at 500k it reads half a
million rows to return six, on every request.

**Integrity.** Nothing prevents an order row with `user_id = 999999` matching no user, and
deleting a user silently orphans their orders.

**Root cause:** `Order.userId` is a plain `Long` rather than a `@ManyToOne User`. Hibernate
generated the index and FK for every table where it saw a mapped association; `orders` has none
to see, so it emitted a bare column. Related to **F7** — the same modelling choice is why
`resolveUserId()` must translate username → id on every request.

### Extended in Unit 9 — the expiry job makes this worse than a user-triggered cost

`OrderExpiryScheduler` runs **every 60 seconds, forever** (`application.yml:67`) and issues:

```sql
SELECT o.id FROM orders WHERE o.status = 'PENDING' AND o.created_at < ?  LIMIT 100
```

There is no index on `status` and none on `created_at` either — `orders` has **only
`PRIMARY KEY (id)`**. So this **full-scans the whole table every minute**, and `LIMIT 100` doesn't
rescue it: in steady state there are usually *zero* expired orders, so it scans every row, finds
nothing, and repeats a minute later.

That is worse than the `getMyOrders` cost, which is only paid when a user acts. This runs **on a
timer regardless of traffic** and grows linearly with total orders ever placed — at a million
orders, a million-row scan every minute, permanently, to usually find nothing.

**Fix:** one Flyway migration covering both access paths:

```sql
ALTER TABLE orders ADD KEY idx_orders_user (user_id);
ALTER TABLE orders ADD KEY idx_orders_status_created (status, created_at);
```

The composite `(status, created_at)` turns the expiry scan into a range seek touching almost
nothing. The FK constraint on `user_id` is a separate judgement call — it needs existing data to be
clean first and it constrains user deletion. **The two indexes are uncontroversial and are what
matter at scale.**

---

## F9 — An absent checkout key returns PENDING, so a correct client can poll forever — `fixed` (high)

**Fixed** in `a58bcf1` (Unit 16, phase 2). Absent key → `ResourceNotFoundException` → 404. Both
"also worth doing" items landed too: the producer's failure callback now writes
`FAILED("could not be queued")`, and the client-side polling bound is part of F8's fix. A key that
is present but *corrupt* deliberately still answers PENDING — corrupt is not absent.

**Where:** `service/OrderService.java:183-194` (and `:102-108`, `producer/OrderEventProducer.java:73-93`)
**Taught in:** Unit 3 (async checkout)

Independent of [F8]. F8 is *the client doesn't poll*; this is *even a correct client may never
terminate*.

```java
String json = redisTemplate.opsForValue().get("checkout:" + checkoutId);
if (json == null) {
    return CheckoutStatusResponse.builder().checkoutId(checkoutId).status("PENDING").build();
}
```

An absent key has at least four causes, and only the first is actually pending:

1. genuinely queued, consumer hasn't written yet
2. the id never existed — typo, stale bookmark, forged value
3. the key expired after its 30-minute TTL
4. Redis restarted or was flushed

**There is no elapsed time at which the API returns anything else.** At 31 minutes the key is
gone, so the fallback fires and answers `PENDING` again — permanently. A client that stops only
on a terminal status polls forever. It's an infinite loop encoded in the API contract.

**Made reachable by two other things:**

- **The Kafka send is fire-and-forget.** `OrderEventProducer:79-89` attaches a `whenComplete`
  that only *logs* on failure — no rethrow, no compensation. `JsonProcessingException` at `:90-92`
  is swallowed the same way. So a broker outage loses the message silently.
- **Write ordering.** `initiateCheckout:102` writes `PENDING` *before* the send is known to have
  succeeded, and returns `202` regardless. So the status record can exist for work that was never
  queued.

The 30-minute TTL is **not** the bug — it's correct memory hygiene, without which Redis
accumulates a key per checkout until it OOMs. The bug is entirely in the read path treating
absence as an ongoing state rather than as absence of information.

**Fix (≈3 lines):** throw `ResourceNotFoundException` → 404 when the key is absent. That gives
the client a decidable state machine where every path terminates: `PENDING` → keep polling,
`SUCCESS`/`FAILED` → stop, `404` → stop and tell the user to check their orders.

**Also worth doing:** in the producer's failure callback, overwrite the key with
`FAILED("could not be queued")` — turns a lost message into an observable failure using machinery
that already exists. And the client should bound its own polling (max attempts + backoff)
regardless of what the server promises.

---

## F8 — The frontend never polls checkout status: the async flow is only half-wired — `open` **(highest severity so far)**

**Where:** `frontend/src/views/CartView.vue:112-124`
**Taught in:** Unit 3 (async checkout)

The backend implements async checkout completely and correctly: `202 Accepted`, a `checkoutId`
correlation id, a Redis status record, a Kafka queue, and a `GET /api/orders/checkout-status/{id}`
endpoint. **The frontend was never converted.** `checkout-status` and `checkoutId` appear
**nowhere** in `frontend/src/`.

```js
await api.post('/orders/checkout')   // discards the response entirely
cart.reset()
router.push('/orders')
```

Consequences, in rough order of severity:

1. **Failures are completely silent.** axios resolves on 2xx, so `202` takes the happy path and
   the `catch` never runs. The consumer writes `FAILED` + reason to Redis, it sits there for its
   30-minute TTL, and **nothing ever reads it**. A sold-out checkout and a successful one look
   identical to the user: cart cleared, redirected to `/orders`, no error.
2. **Silent failure manufactures duplicate orders.** Seeing no order, the user retries.
   `initiateCheckout` mints a *new* `checkoutId`, so `ConsumerIdempotencyGuard` (which keys on
   checkoutId) cannot recognize it as the same intent — it dedupes redelivery of one message, not
   two distinct requests. Both proceed → two orders, two stock deductions, one intended purchase.
3. **Even a successful checkout shows an empty order list.** `router.push('/orders')` fires
   `GET /api/orders/me` within milliseconds, while the order needs Kafka produce → consumer poll
   → lock acquisition → N deductions → `persistOrder` commit. The fetch wins that race almost
   always.
4. **The error message names the one failure it cannot detect.** `:120` says "Some items may be
   sold out," but sold-out is decided asynchronously. The `catch` can only fire on synchronous
   failures (empty cart 400, auth, network, 500).
5. **Local and server cart diverge.** `cart.reset()` (`:117`) clears the badge, but the server
   cart is only emptied inside `persistOrder` (`OrderService:249`). On failure the server still
   holds the items while the UI shows empty.

**Why it was never caught:** the JMeter tests hit the API directly, so the flagship feature — the
one the README leads with and the entire Kafka narrative rests on — is not exercised end to end
by the app's own UI.

**Fix:** capture the `checkoutId` from the 202, poll `GET /api/orders/checkout-status/{id}` on an
interval with a timeout and backoff, show a pending state, and navigate only on `SUCCESS` (using
the returned `orderId`) or surface `failureReason` on `FAILED`. Reuse the existing endpoint —
no backend change needed. Consider also making `initiateCheckout` idempotent per cart state so
retries can't double-order.

---

## F7 — `resolveUserId()` costs a DB query on every authenticated request — `open` (optimization + a 500 that should be a 401)

**Where:** `security/SecurityUtils.java:14-17`
**Found in:** Unit 2 (SecurityContextHolder)

```java
public Long resolveUserId() {
    String username = SecurityContextHolder.getContext().getAuthentication().getName();
    return userRepository.findByUsername(username).orElseThrow().getId();
}
```

**Two separate issues.**

**(a) An avoidable SELECT per request.** The access token's `sub` carries the *username*, but the
app works in `userId` (the MySQL PK stored on `Order.userId`, `Cart.userId`, …). So every call
translates one to the other via the `users` table. Called from `OrderService:86`, `:272`,
`:302`, `:313` and the cart/product paths — i.e. on essentially every authenticated request.

Fix: add the id as a claim in `JwtUtil.generateAccessToken` (`.claim("uid", userId)`) and read
it from the parsed token. Zero queries. Safe to cache in a token because a user's numeric id is
**immutable** — unlike `role`, which is why role has a staleness problem and `uid` wouldn't.

**Extended in Unit 10 — it's actually two lookups per request.** `UserActionLogService:24` does the
*same* `findByUsername` translation again, on the `mongoLogExecutor` thread, for every authenticated
request. So one request resolves the identical username→userId mapping from MySQL **twice** — once
on the request thread via `resolveUserId()`, once on the log thread. A single `uid` claim removes
both.

**(b) A deleted user with a live token gets a 500.** `.orElseThrow()` with no argument throws a
bare `NoSuchElementException` → `GlobalExceptionHandler:51` → **500**. The token is valid but
its subject no longer exists, which is a **401**. Same 4xx/5xx inversion family as F4. Fixing
(a) makes this path disappear for reads but it still applies wherever the user row is needed.

---

## F6 — `JwtUtil` javadoc claims a 15-minute access token; it's actually 5 — `fixed` (doc only, trivial)

**Fixed** in `4297c85` (Unit 16, phase 1). Resolved by *removing* the duration rather than
correcting it — the config is authoritative and the javadoc now names the property.
**The finding was incomplete:** the same stale "15m" also appeared in
`AuthService.logout`'s javadoc, fixed in the same commit.

**Where:** `security/JwtUtil.java:28` vs `resources/application.yml:63`

```java
/** Short-lived access token (15m). Carries username + role. */
```
```yaml
access-expiration-ms: 300000     # 5 minutes
```

The config wins. Trivial, but the access-token lifetime *is* the revocation exposure window,
so a stale figure here is exactly the number someone would quote wrongly while reasoning about
an incident. Fix the comment, or drop the duration from it since the config is authoritative.

---

## F5 — `UserActionLogFilter`'s ordering comment states a rationale that isn't load-bearing — `fixed` (doc only, low severity)

**Fixed** in `59fa2a6` (Unit 16, phase 1). The comment now states the real constraint —
the filter must sit inside `SecurityContextHolderFilter`, which clears the context in a
`finally` on the outbound pass — and says explicitly that position relative to `JwtFilter`
does not matter.

**Where:** `filter/UserActionLogFilter.java:18-19`
**Found in:** Unit 2 (servlet filter chain)

The class comment says:

> *Logs each authenticated request to MongoDB (user_action_log). Sits after JwtFilter, so the
> Authentication is already resolved when it runs.*

But the filter calls `filterChain.doFilter(...)` as its **first** statement (`:37`) and reads
`SecurityContextHolder` afterwards (`:39`) — all its work is on the **outbound** pass. So
`JwtFilter` would populate the context downstream regardless of registration order, and the
filter would work identically if placed *before* `JwtFilter`.

The real constraint is different: it must sit inside the region where the `SecurityContext`
still exists, i.e. after `SecurityContextHolderFilter` (which clears the context in a
`finally` on the way out).

**Not a bug** — behavior is correct and the ordering is harmless. But the stated reason is
wrong, and someone refactoring the chain later could rely on it and draw a false conclusion.
Fix by correcting the comment to describe the outbound-pass behavior.

---

## F4 — Duplicate username registration returns 500 instead of 409 — `fixed`

**Fixed** in `72c8662` (Unit 16, phase 2). `DuplicateUsernameException` → 409. The write-up's
"worth grepping for other bare `throw new RuntimeException(...)` sites" was done and the results
are logged as **F23** rather than fixed piecemeal here.

**Where:** `service/AuthService.java:37`, `exception/GlobalExceptionHandler.java:51-54`
**Found in:** Unit 1 (HTTP status semantics) — found by reasoning from the 4xx/5xx contract

```java
if (userRepository.existsByUsername(request.getUsername())) {
    throw new RuntimeException("Username already taken");
}
```

A bare `RuntimeException`, which `GlobalExceptionHandler:51` maps to
`INTERNAL_SERVER_ERROR`. So a client picking a taken username is told **the server broke**.

This is the same 4xx/5xx blame inversion that was already fixed once at the handler level
(`RuntimeException` used to map to 400) — the handler is now right, but this throw site
never got a proper exception type, so it lands in the catch-all.

**Fix:** a dedicated exception (e.g. `DuplicateUsernameException`) mapped to `409 CONFLICT`,
which is the textbook code for "well-formed request, conflicts with existing state."
`SoldOutException` at `GlobalExceptionHandler:24` is the pattern to copy. Worth grepping for
other bare `throw new RuntimeException(...)` sites in service code at the same time —
`OrderService.processCheckout` has two on the lock-failure paths.

---

## F3 — Second deletions fall arbitrarily behind: a 500ms sleep caps the pool at ~16/sec — `open` (REVISED)

**Where:** `service/ProductCacheService.java:85-94`, `config/AsyncConfig.java:17-27`
**Taught in:** Unit 5

### Correction to the original F3

The original claim — *"`UserActionLogFilter` reuses `cacheEvictExecutor` for MongoDB writes"* — is
**FALSE**. It was carried from a 49-day-old memory note and logged without verification.
`UserActionLogService:21` is `@Async("mongoLogExecutor")`, a **separate** pool
(`AsyncConfig:29-37`: core 2, max 8, queue 500, DiscardPolicy). The executors are properly
separated. **Lesson: verify stale notes against the code before logging them as findings.**

### What is actually true

`scheduleSecondDeletion` **blocks** its worker on `Thread.sleep(500)` (`:88`) instead of handing
the work to a scheduler. Against the pool config (`AsyncConfig:20-24`: core 8, max 32, queue
**2000**, CallerRunsPolicy) that produces a low ceiling:

- Each task holds a thread for 500ms, so **2 tasks/thread/sec**
- **8 core threads gives ~16 second-deletions/sec sustained**
- `ThreadPoolTaskExecutor` only grows past core size **once the queue is full**. With a 2000-deep
  queue it will effectively never reach 32 threads — it queues instead

Under load the queue grows and second deletions execute **minutes late**, which doesn't merely
waste threads — it **defeats the mechanism**. The second deletion exists to close a race window
measured in milliseconds around the write. Firing it two minutes later evicts a long-since-correct
entry while leaving the actual stale window uncovered.

A flash sale at 100 checkouts/sec x 2 items needs 200 deletions/sec against ~16/sec capacity. The
backlog grows until CallerRunsPolicy triggers, at which point the **Kafka listener thread itself**
blocks 500ms per eviction.

**Fix:** use a `ScheduledExecutorService` (`schedule(task, 500, MILLISECONDS)`) so the delay costs
no thread at all. Capacity then becomes "how fast can Redis accept DELETEs," which is enormous.

---

## F23 - Bare `RuntimeException` is still thrown at four service sites - `open` (low-medium)

**Where:** `service/AuthService.java:72`, `:78`; `service/OrderService.java:139`, `:156`
**Found in:** Unit 16 phase 2, while fixing **F4**

F4's write-up said to grep for other bare `throw new RuntimeException(...)` sites in service code.
Done — there are four, and every one lands in `GlobalExceptionHandler:51`'s catch-all as a **500**:

| Site | Condition | Should be |
|---|---|---|
| `AuthService.refresh:72` | signature invalid / expired | **401** |
| `AuthService.refresh:78` | token revoked or unknown jti | **401** |
| `OrderService.processCheckout:139` | Redisson `tryLock` timed out | **503** (retryable) |
| `OrderService.processCheckout:156` | lock wait interrupted | **503** (retryable) |

The two auth ones are the more serious: a client cannot distinguish "your session ended, log in
again" from "the server is broken", so a correct client has no way to know it should re-authenticate.

The two checkout ones are subtler and interact with **F1**. `processCheckout` runs on the Kafka
listener thread, so its exception is not shaped into an HTTP status at all — it is caught by
`CheckoutRequestedConsumer`'s generic handler and **rethrown to trigger a retry**, which is the
right behaviour. So the fix there is *not* simply a mapped exception type: a dedicated
`LockUnavailableException` would make the retry decision explicit rather than incidental, and would
stop the same throw producing a 500 if `processCheckout` is ever called synchronously.

**Deliberately not fixed alongside F4.** These are one habit, not four bugs, and the right shape is
probably a small status-carrying exception base with one handler reading it — the same conclusion
DocPlatform reached for its F33/F5 pair. Fixing them one at a time is what produced this spread.

---

## F24 - The `checkout:{id}` record is hand-rolled in three classes - `open` (low)

**Where:** `service/OrderService.java` (write PENDING, read status),
`kafka/consumer/CheckoutRequestedConsumer.java` (`writeStatus`, `handleDlt`'s read),
`kafka/producer/OrderEventProducer.java` (`markCheckoutFailed`, added in phase 2)
**Found in:** Unit 16 phase 2

The key format `"checkout:" + id`, the `status-ttl-minutes` TTL, the Jackson round-trip and the
"never throw on a Redis failure" rule are now duplicated across **three** classes. Phase 2 had to
apply the same catch-broadening in each of them independently, and adding the producer's
`markCheckoutFailed` meant copying the format and TTL a third time.

Nothing is wrong today — the copies agree. The risk is that they are only kept in agreement by
someone remembering to update all three, and the TTL in particular is read from config separately in
each class via its own `@Value`.

**Fix:** a `CheckoutStatusStore` owning the key, the TTL, serialization, the absent → 404 rule
(**F9**) and the never-throw rule (**F1**). Roughly 40 lines, and it deletes more than it adds.

**Why it was not done in phase 2:** `CheckoutRequestedConsumerTest` mocks `StringRedisTemplate` and
`ValueOperations` directly, so introducing the store would have required rewriting the mocking in
all 7 existing consumer tests *in the same change as the F1 correctness fix* — churn against the
very tests guarding that path. Worth doing as its own change, with the tests migrated deliberately.
