# 10 — MongoDB audit trail

Unit 10, closing Block B. Absorbs `TECH_OVERVIEW.txt` §3, with one correction.

---

# Concept 1 — Why a second database at all ✅

## Document vs relational

| | Relational (MySQL) | Document (MongoDB) |
|---|---|---|
| Unit of storage | a **row**, fixed columns | a **document**, fields vary per document |
| Schema | declared up front, enforced on write | none required |
| Relationships | foreign keys + `JOIN` | embed, or store an id and look it up yourself |
| Transactions | full ACID | limited; the design assumes you rarely need them |
| Good at | invariants that must always hold | high write volume, evolving shape |

`OrderActivityLog:21` shows what that buys:

```java
private Map<String, Object> metadata;
```

An arbitrary bag. In MySQL that needs a JSON column, a key-value side table, or a migration per
new field. For audit records — where next month you'll want a field you haven't thought of — that
flexibility is the point.

## The justification

A second database is usually a mistake: double the operations, failure modes, and backup story.
It's justified when the data has **genuinely different requirements**:

| | Orders, stock, carts | Audit logs |
|---|---|---|
| Losing one is | **unacceptable** | annoying |
| Volume | one row per purchase | one document per **HTTP request** |
| Shape | stable, invariant-bearing | evolving, additive |
| Needs transactions | yes | no |
| Write speed | moderate | very — on every request |

That's **polyglot persistence** — storage chosen per workload.

The split is disciplined: **MongoDB holds nothing the application reads back to make a decision.**
No business logic queries these collections. Write-mostly, read-by-humans-later.

> The test for "should this be a separate datastore" isn't "is it a different shape." It's **"do I
> need different guarantees, and can I tolerate losing it?"**

---

# Concept 2 — The best-effort write pattern ✅

| Collection | Written by | Events |
|---|---|---|
| `order_activity_log` | `OrderEventConsumer:40-93` | `ORDER_CREATED`, `PAYMENT_COMPLETED` |
| | `OrderExpiryScheduler:110-119` | `EXPIRED_CANCELLED` |
| `user_action_log` | `UserActionLogService:21-36` | every authenticated request |

Principle: **a MongoDB failure must never fail a checkout.** Logs are observability data, not
business state, so the audit path is subordinate to what it observes.

`user_action_log` implements this in four independent layers:

1. **Off the response path** — `UserActionLogFilter:37` calls `doFilter` **first**, so logging runs
   on the outbound pass (→ `02`). This both keeps it off the latency path *and* means it records
   what actually completed rather than what was attempted.
2. **Off the request thread** — `@Async("mongoLogExecutor")`, returns immediately.
3. **Small pool + `DiscardPolicy`** (`AsyncConfig:29-37`: core 2, max 8, queue 500) — when the
   queue fills, entries are **silently dropped** rather than blocking anything.
4. **Swallowed exceptions** — `try/catch` logging a warning (`:32-35`).

Plus anonymous requests are skipped (`UserActionLogFilter:40-42`) — no userId to attach.

## Why silent dropping is acceptable

Because **nothing in the application reads it back to make a decision**. What would have to change:
anything starting to *depend* on it — rate limiting, fraud detection, usage billing, or a
**compliance requirement** to prove who did what. "We drop some under load" is fine for a debugging
aid and a legal problem for an audit record. The data wouldn't change; only its **role** would, and
the code wouldn't notice.

## Correction to `TECH_OVERVIEW.txt` §3c

The note claims *"Both MongoDB write paths are wrapped in try/catch... any exception is logged as a
warning and swallowed."* Two of the three are. **`OrderEventConsumer:62` is not** — its only
`try/catch` guards `objectMapper.readValue` (`:47-52`), so a Mongo failure throws and reaches
`@RetryableTopic`.

That's arguably **better** — a transient blip is retried instead of lost — and the ordering is
correct, since `markProcessed` at `:64` runs only after a successful save, keeping the retry clean
(→ `08`).

**But the retry window is ~7 seconds** (1s + 2s + 4s) against outages measured in minutes. So
within seconds every `order-created` event lands in the DLT, where `OrderEventConsumer:96-100` only
logs *"manual intervention required"* and nothing drains it. **The retries protect against a blip
they'll rarely meet, and not at all against the outage they'd matter for.**

## A ten-minute MongoDB outage

| Writer | Behaviour | Recoverable? |
|---|---|---|
| `UserActionLogService` | throws → caught → warned → swallowed; pool keeps accepting, each fails fast | ❌ gone |
| `OrderExpiryScheduler` | same; cancellation still proceeds correctly | ❌ gone |
| `OrderEventConsumer` | throws → 4 attempts over ~7s → **DLT** | ⚠️ in the DLT until retention expires |

Requests and orders are **entirely unaffected** — the design goal holds. By volume the biggest loss
is `user_action_log`; by value it's `order_activity_log`, the lifecycle trail you'd need to
reconstruct the outage. Ironically the only writer with any recovery path is the one the notes claim
swallows exceptions.

## Gotcha — a second username lookup per request

`UserActionLogService:24` does `userRepository.findByUsername(username)`. Combined with
`SecurityUtils.resolveUserId()`, an authenticated request resolves the **same** username→userId
mapping from MySQL **twice** — once on the request thread, once on the log thread. A `uid` claim in
the JWT eliminates both. (→ **F7**)

---

# Concept 3 — MongoDB itself (what ShopHub doesn't exercise) ✅

**ShopHub uses roughly 5% of MongoDB.** Two flat documents, `save()` only, no queries, no
aggregation, empty repository interfaces. Verified by grep: **no indexes anywhere** beyond the
automatic `_id`, no TTL, no query methods, and nothing in the codebase ever reads these collections.

So the following is MongoDB-specific knowledge that this codebase gives no occasion to learn — and
it's the material an interviewer goes to immediately after "why MongoDB?"

## Indexes are explicit, and Mongo won't warn you

Same concept as `04a`, but MySQL at least has a schema you can inspect. In Mongo you declare
indexes in code (`@Indexed`, `@CompoundIndex`) or via the shell, and absent that, every query is a
**collection scan** — silently, forever. (→ **F15**)

## TTL indexes

Mongo can delete documents past an age, with no job to write:

```java
@Indexed(expireAfterSeconds = 2592000)   // 30 days
private LocalDateTime timestamp;
```

This is *the* standard tool for audit logs and is exactly what's missing here. (→ **F16**)

## The aggregation pipeline

Mongo's real query power — `$match`, `$group`, `$sort`, `$lookup`, chained as stages. It's how you'd
actually *use* an audit trail: "requests per user per hour," "orders created but never paid."
`MongoRepository` exposes none of it; you'd use `MongoTemplate` or `@Aggregation`.

## Write concern — and it qualifies the "best-effort" claim

`w:1` (default) = one node acknowledged. `w:majority` = a majority of a replica set did. On a
**standalone `mongod`**, an acknowledged write can still be lost in a crash before the journal
flushes. So these writes are *more* best-effort than the design intends. Fine for audit logs — but
knowing it is the line between using Mongo and understanding it.

## Other things worth knowing

- **Document design** — embed vs reference, and the **16 MB document limit**. `metadata` as an
  unbounded `Map<String, Object>` is a trap waiting for a large payload.
- **`_id` is an `ObjectId` that encodes a creation timestamp** — so sorting by `_id` sorts by
  insertion time for free, and you can range-query on it. These documents carry a separate
  `timestamp`, which is more correct (event time vs insertion time), but the id already gives you one.
- **Schema flexibility cuts both ways** — no enforcement means malformed documents enter silently
  where MySQL would have rejected them.
- **A standalone `mongod` has no multi-document transactions** — those need a replica set. Not a
  problem for append-only logs, but it's the standard follow-up question.
- **Replica sets and sharding** — replication for durability/failover, sharding for horizontal
  write scale. The single-node deployment here has neither.

## Still uncovered

Query syntax hands-on, the aggregation pipeline in practice, explain plans (`explain()`), and
operational concerns (backups, oplog, replica-set elections). Learning those means going **beyond
this codebase**, since ShopHub never calls for them.

## Questions I should be able to answer

- Name three concrete differences between document and relational storage.
- What does `Map<String, Object> metadata` buy that MySQL would charge for?
- What's the real test for whether data deserves its own datastore?
- What makes MongoDB losable here, in one sentence?
- Name the four layers protecting the request from the audit write.
- Why does `UserActionLogFilter` call `doFilter` first? Give both reasons.
- What would have to change for silent dropping to become unacceptable?
- Which of the three writers behaves differently from the other two, and is that better or worse?
- Why don't the retries help during a real outage?
