# Drill Questions — Q&A bank

Questions asked during study, with how I answered and what was wrong. The point is the
record of *misconceptions corrected*, not a list of right answers — reread the ✗ entries.

Legend: ✓ answered correctly · ~ partially · ✗ missed

---


## CONSOLIDATION — final self-check (2026-08-04) — PASSED

Three rounds, 26 questions, closed-book. Round 1: 10 full / 5 partial / 1 weak.
Rounds 2-3 closed the gaps. Remaining issues were phrasing, not understanding.

### Solid
Conditional UPDATE family (`rows == 0` as business logic; why inventory needs a lock and order
state does not); rollback vs compensation; containers vs VMs; `resource` vs `data`; `noDataState`;
Spring proxies (after reteach).

### The three that needed a second pass — reread these exact phrasings

**Spring proxy / self-injection.** `@Transactional` provides **two** things: a transaction **and an
open persistence session**. Lazy loading only needs the **session**. Bypassing the proxy removes
both — but on a **Tomcat** thread `open-in-view` supplies a session anyway, so it silently works;
on a **Kafka listener** thread there is no HTTP request, no session, and it throws
`LazyInitializationException`. Same code, passes through a web endpoint, fails on the async path.

**Why delete rather than overwrite the cache.** Not efficiency, and not "a write might fail" (a
delete can fail too). The cause in one sentence: **nothing coordinates the order of cache writes
with the order of database commits** — two systems, two latencies, so a writer that commits *first*
can touch the cache *last*:

```
A: UPDATE db -> 100
B: UPDATE db -> 200        <- DB truth is 200
B: SET cache = 200
A: SET cache = 100         <- permanently wrong until TTL
```

Deletion is **idempotent and order-independent**, so any interleaving converges.

**OIDC.** The ARN is an **identifier, not a credential** - publishing it is safe. The trust policy
*checks* that `sub` matches `repo:owner/repo:ref:refs/heads/main`; what makes the claim
**unforgeable** is that **the signature covers the claims** and only GitHub holds the signing key.
An attacker can write any `sub`; they cannot produce a valid signature over it.
> Matching is the check. Signing is what makes the claim worth checking.

### Vocabulary supplied (concepts were known, terms weren't)

| Concept | Term |
|---|---|
| read stale, then write based on it | **read-modify-write** / **lost update** |
| the gap between checking and acting | **TOCTOU** (time-of-check to time-of-use) |
| manual undo instead of rollback | **compensation**, within a **saga** |
| same result however many times applied | **idempotent** |
| storage chosen per workload | **polyglot persistence** |
| one message redelivered vs two distinct requests | **message-level vs intent-level idempotency** |

---

## Unit 3 — async checkout (comprehension check, mechanism-first)

**~ Name the threads a single checkout touches.**
My answer: gave the correct *stage sequence* (Redis PENDING → Kafka → 202 → consumer deducts →
Redis finished) but never separated it into threads.
Correction: five threads — Tomcat (accept, then **freed**), producer network thread (ships bytes),
listener (locks + deductions + `persistOrder`), cacheEvict (500ms second delete), listener 2
(Mongo audit). The load-bearing fact is that **Tomcat is released before any work happens** —
that's what stops 10,000 checkouts exhausting 200 threads. Also: the 202 does **not** wait for
Kafka to receive; `send()` returns immediately with no confirmation. That ordering is what makes
F9 reachable.

**✗ Why publish an in-process event + AFTER_COMMIT instead of `kafkaTemplate.send()` inside
`persistOrder`?**
My answer: wasn't sure.
Correction: two failures. **(a)** The tx is still open when you'd send. A later rollback means row
123 never existed, but `order-created` for orderId=123 is already in Kafka — the audit consumer
logs a nonexistent order, and **you can't retract it** because the log is append-only. The lie is
permanent. **(b)** Even without rollback, the consumer may query MySQL *before* commit, find
nothing (uncommitted data is invisible to other connections), and fail or write garbage — a
read-your-own-write race across processes. AFTER_COMMIT kills both.

**~ What does using `checkoutId` as the Kafka message key determine?**
My answer: idempotency, "also an index."
Correction: dedup is a real secondary use, but the **primary** job is choosing the **partition**
(`hash(key) % 3`). That puts all messages for one checkoutId on the same partition → ordered, and
handled by **one** consumer. Without it a redelivery could land on another partition and run
**concurrently** with the original. Not an index — a partition selector.

**✗ Why is `SoldOutException` terminal while a DB outage is retried?**
My answer: muddled — "db transactional sets fallback solution."
Correction: **permanent vs transient.** Sold out is permanent — retrying at 1s/2s/4s cannot
conjure stock, so retries are waste *and* delay telling the user. A DB outage is transient — it
may be back. **Retry transient failures, never permanent ones.** That's why `SoldOutException` is
its own type: so the consumer can distinguish "never will work" from "didn't work yet."

**~ What breaks if `markProcessed` runs before the status write?**
My answer: "Redis as source of truth, only truth changes then it changes."
Correction: Redis isn't the source of truth — the `orders` table is; Redis holds a notification.
The real reason: `markProcessed` asserts *"all side effects complete, never do this again,"* so it
must be last. Swap them → mark → crash before `writeStatus` → offset uncommitted so Kafka
redelivers → guard says "already processed, skip" → order exists, stock deducted, status
**PENDING forever**, and the idempotency protection is what *prevents* the fixing retry. **An
idempotency marker goes after the work; marking early turns at-least-once into at-most-once and
silently loses work.**

### Code references for the five answers above

⚠️ Earlier in the session these were cited from a `cat` dump with no line numbers and were off by
2–4 lines. **These are the verified ones.**

**Q1 — the five threads**

| Thread | Code |
|---|---|
| Tomcat: accept → 202 | `OrderController:25-28` → `OrderService.initiateCheckout:85-118` |
| — cheap validation only | `OrderService:86-93` |
| — mint correlation id | `OrderService:95` |
| — Redis PENDING | `OrderService:102-105` |
| — hand off / return | `OrderService:110-114`, `:117` |
| Producer network thread | `OrderEventProducer:76-77` (`send()` returns a future), `:79-89` (callback only logs) |
| Listener: the real work | `CheckoutRequestedConsumer:46-50` → `OrderService.processCheckout:129-177` |
| — why `userId` is a parameter | `OrderService:122-124` (comment), `:129` (signature) |
| cacheEvict pool | `ProductCacheService:85-86` (`@Async`), `:88` (`Thread.sleep(500)`), `:92` |
| Listener 2: Mongo audit | `OrderEventConsumer:40-65` |

**Q2 — AFTER_COMMIT vs a direct send:** `persistOrder` is transactional at `OrderService:226-227`;
publishes an in-process event at `:253-254`; the bridge is `OrderEventKafkaBridge:28-38` with the
rationale in its class doc `:13-20` and the residual crash gap at `:18-19`.

**Q3 — the message key:** passed at `OrderEventProducer:77`; 3 partitions at
`KafkaTopicConfig:34-38` (also `:20`, `:28`); received at `CheckoutRequestedConsumer:50`
(`@Header(KafkaHeaders.RECEIVED_KEY)`); feeds the guard at `:52` → `ConsumerIdempotencyGuard:24-26`.

**Q4 — permanent vs transient:** the conditional UPDATE at `ProductInventoryRepository:19-21`;
`rows == 0` → `SoldOutException` at `OrderService:145-148`; terminal handling at
`CheckoutRequestedConsumer:81-89`; everything else rethrown at `:91-96` (comment `:92`); retry
policy `:46`; DLT `:99-124`.

**Q5 — mark last:** `writeStatus` then `safeMarkProcessed` at `CheckoutRequestedConsumer:70-77`
(the why, inline, at `:75-76`); same order on the sold-out path `:84-89`; the mark at `:126-133` →
`ConsumerIdempotencyGuard:28-30`; the rule stated in the guard's class doc `:13-15`; the Redis-blip
case acknowledged in the DLT guard at `:104-105`.

**Resolved from Unit 1:** I'd flagged uncertainty over whether `deductStock` carried
`@Transactional`. **It does** — `ProductInventoryRepository:17`, comment `:15-16`: *"commits its own
short tx so the deduction is durable before the lock releases."* So each deduction commits
**inside** the lock. Load-bearing for Unit 7.

---

## Unit 1 — HTTP, nginx, the container network

**~ Where does the request go after nginx receives it on port 80?**
My answer: nginx reverse-proxies to `localhost:8080`.
Correction: it's `proxy_pass http://app:8080` (`infra/nginx/nginx.conf:15`). `localhost`
inside the nginx container *is the nginx container* — no JVM there, so that would be a 502.
`app` is the Compose **service name**, resolved by Docker's embedded DNS at 127.0.0.11.

**✗ If you deleted `proxy_set_header Host $host;`, what would the app see and what breaks?**
My answer: didn't know; guessed clients couldn't access it.
Correction: the header wouldn't go *missing* — nginx defaults to
`proxy_set_header Host $proxy_host`, so the app would see `Host: app:8080`. A wrong value,
not an absent one. **Nothing in ShopHub breaks today** (verified: no
`ServletUriComponentsBuilder` / `fromCurrentRequest` / `ResponseEntity.created`, no cookies,
frontend uses relative `baseURL: '/api'`, `server_name _` is a catch-all). It becomes an
outage the moment anything generates an absolute self-URL — a 201 `Location`, pagination
links, a password-reset email — which would render as `http://app:8080/...`, resolvable only
inside the Docker network. Vicious because it passes every test that doesn't go through nginx.
