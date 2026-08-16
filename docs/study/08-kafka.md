# 08 — Kafka

Unit 8. Vocabulary and the full message flow are in `03-async-checkout.md` Concept 3; this file
goes underneath it.

---

# Concept 1 — The log, and who reads it ✅

## Kafka stores an append-only file

A partition is literally **a file that only ever gets appended to** — no updates, deletes, or
reordering. New messages go at the end and get the next sequential number.

```
partition 0:  [ msg ][ msg ][ msg ][ msg ][ msg ]
offset:          0      1      2      3      4    ← next write goes to 5
```

**It's fast because appending is the one thing disks do well** — no seeking, no index rebalancing,
no page splits. Kafka also hands the file straight to the network socket via `sendfile()`, the
same zero-copy trick nginx uses for static files (→ `01`). The data never enters application memory.

**Reading doesn't remove anything.** This is what makes Kafka *not a queue*. Consuming means
reading at a position and remembering where you got to.

## The offset is the only consumer state

Kafka keeps, per **(consumer group, topic, partition)**, one number: the **committed offset**.
That single number is why:

- **Replay is possible** — reset it backwards and reprocess. `auto-offset-reset: earliest`
  (`application.yml:42`) means a *brand-new* group starts at 0 and reads everything still within
  retention.
- **Independent consumers work** — two groups on the same topic keep separate offsets and don't
  affect each other. Add an analytics consumer tomorrow; it reads all history without disturbing
  `flash-sale-group`.
- **At-least-once is inevitable** — the offset commits *after* the handler returns.

**Messages are deleted by retention policy, not by consumption.** A message sits there whether it
was read zero times or a thousand.

## Consumer groups and assignment

**Each partition is assigned to exactly one consumer in the group.**

```
topic checkout-requested, 3 partitions, group flash-sale-group
 1 consumer:   A ← 0,1,2
 3 consumers:  A ← 0    B ← 1    C ← 2
 4 consumers:  A ← 0    B ← 1    C ← 2    D ← idle
```

**Partition count is the parallelism ceiling.** A 4th consumer gets nothing. This makes partition
count a capacity decision made up front — raising it later re-shuffles which key lands where.

## Rebalancing

When a consumer joins or leaves, Kafka reassigns partitions. It also does this when a consumer
*appears* dead — no `poll()` within `max.poll.interval.ms` (default 5 min).

That's the concrete trigger for the check-then-act window below: a slow consumer is declared dead,
its partition is reassigned, the new owner starts from the last **committed** offset, and the
original is still working. Two consumers process the same message concurrently. When the original
finishes, its offset commit is **rejected** (`CommitFailedException`) — work done, unacknowledged.

## Gotcha — the parallelism here is latent

`@KafkaListener` uses `ConcurrentKafkaListenerContainerFactory`, whose **default concurrency is
1**, and nothing overrides it. So despite 3 partitions, **one thread drains all three
sequentially**. Throughput is bounded by a single thread doing lock + deduct + persist per
message. Nothing breaks — the queue still absorbs the spike — but it drains at a third of the
intended rate. (→ **F14**)

---

# Concept 2 — Ordering, and what retries cost ✅

> Kafka guarantees ordering **within a partition**. Nowhere else.

The key picks the partition, so messages sharing a key are ordered relative to each other.
Different keys have **no ordering relationship at all**, even if produced a second apart.

## Retry topics break that guarantee

`@RetryableTopic` does **not** hold the message and retry in place. It **republishes to a
different topic** and commits the original offset immediately.

```
partition 1:   [ msg-A ][ msg-B ][ msg-C ]
                   └── throws → republished to checkout-requested-retry-0
                                offset committed → partition 1 moves on
partition 1 continues:   msg-B, msg-C process
retry topic (~1s later): msg-A processes
```

**msg-A completes after msg-B and msg-C.** Ordering is gone.

| You gain | You lose |
|---|---|
| A slow-failing message doesn't block everything behind it | Strict per-partition ordering |

No configuration gives both. Retry-in-place preserves order and blocks the partition;
retry-via-topic keeps the partition moving and reorders.

## Why that costs ShopHub nothing

- **`checkout-requested`** — each message is an independent checkout keyed by a unique UUID. Two
  checkouts have no ordering relationship to preserve.
- **`order-created` / `payment-completed`** — both keyed on `orderId`, but they are **separate
  topics**, so they were never ordered relative to each other. Within one topic, two
  `order-created` for the same order shouldn't exist.

Nothing here depends on cross-message ordering, so the sacrifice is free. *"We gave up ordering and
it costs nothing because every message is independent"* is a much stronger answer than *"Kafka has
retry topics."*

## The retry topology

```java
@RetryableTopic(attempts = "4", backoff = @Backoff(delay = 1000, multiplier = 2.0),
                autoCreateTopics = "true")
```

Spring Kafka provisions extra topics at startup; with non-fixed backoff it typically creates one
retry topic per attempt plus a dead-letter topic:

```
checkout-requested → -retry-0 (~1s) → -retry-1 (~2s) → -retry-2 (~4s) → -dlt
```

`attempts = 4` is the **original attempt plus three retries**. Each retry topic has its own
consumer that waits out the delay — which is why the delay costs no thread on the main partition.

**The DLT is terminal** — nothing retries from there. The two handlers differ:

- `CheckoutRequestedConsumer:99-124` writes `FAILED` to Redis, guarding against overwriting an
  existing `SUCCESS` (`:107-115`)
- `OrderEventConsumer:96-100` **only logs** — *"manual intervention required"*. An audit write that
  exhausted its retries is silently absent from MongoDB.

---

# Concept — Idempotent consumers ✅ (taught early)

## What problem it solves

Kafka commits the offset **after** the handler returns, so any crash in between causes
**redelivery**. That's the guarantee, not a bug. The alternatives are worse:

- Commit before processing → **at-most-once**: a crash silently loses the checkout.
- True exactly-once across Kafka *and* MySQL *and* Redis needs distributed transactions —
  expensive, and not what this stack does.

Duplicates are therefore a certainty over time, and the messaging layer cannot prevent them.
**The handler must tolerate them.**

## What "idempotent" means

Applying an operation N times gives the same result as applying it once — `f(f(x)) = f(x)`.

```sql
UPDATE stock SET available = 5              WHERE id = 1;   -- idempotent
UPDATE stock SET available = available - 2  WHERE id = 1;   -- NOT idempotent
```

The second form is exactly what checkout does (`ProductInventoryRepository:19-21`), so
**`processCheckout` is inherently non-idempotent** — twice means two orders and a double
deduction. It needs external protection.

Two ways to get it:

1. **Natural idempotency** — upsert on a unique key, or a conditional update whose second
   application is a no-op.
2. **A dedup guard** — remember what's been done, skip repeats. ← ShopHub uses this.

## The guard

`ConsumerIdempotencyGuard` — 12 lines of logic:

```java
private static final Duration TTL = Duration.ofHours(24);                       // :21

public boolean isAlreadyProcessed(String topic, String messageKey) {            // :24-26
    return Boolean.TRUE.equals(redisTemplate.hasKey("kafka:processed:" + topic + ":" + messageKey));
}
public void markProcessed(String topic, String messageKey) {                    // :28-30
    redisTemplate.opsForValue().set("kafka:processed:" + topic + ":" + messageKey, "1", TTL);
}
```

| Consumer | Check | Mark |
|---|---|---|
| `CheckoutRequestedConsumer` | `:52-55` | `:77` (success), `:89` (sold out), via `:126-133` |
| `OrderEventConsumer` | `:41-44`, `:70-73` | `:64`, `:93` |

## Three details that are right

**The topic is part of the key.** `order-created` and `payment-completed` **both key on
`orderId`** (`OrderEventProducer:28` and `:52` are identical). Drop the topic prefix and:

1. Order 42 created → `ORDER_CREATED` logged → `markProcessed("42")`
2. User pays → `payment-completed` key `"42"` → already present → early return at
   `OrderEventConsumer:70-73`
3. **`PAYMENT_COMPLETED` is never written**

Why that's nastier than it looks: it's **asymmetric** (order-created always precedes payment, so
it always wins — you'd lose *every* payment audit record, not intermittently); it's **silent**
(logs `"Duplicate key=42 — skipping"`, indistinguishable from correct dedup); and
`checkout-requested` **wouldn't collide** because it keys on a UUID, so testing a checkout flow
would pass cleanly and hide it.

> **A dedup key must be unique across every namespace that shares it.** A message key is only
> unique *within* a topic — Kafka guarantees nothing across topics.

**TTL is 24h** (`:21`) — outlasts any retry window (`@RetryableTopic` at
`CheckoutRequestedConsumer:46` is 4 attempts, 1s/2s/4s), while still bounding Redis growth. A
permanent key would leak forever.

**The mark happens after the work** (`:70-77`, reasoning inline at `:75-76`). Marking early turns
at-least-once into at-most-once, and the guard then *blocks* the retry that would have healed the
gap. `safeMarkProcessed` (`:126-133`) swallows failures from the mark itself — the status is
already written, so let the offset commit rather than trigger a pointless retry.

## Two limits, different in kind

### 1. Check-then-act is not atomic

```java
if (idempotencyGuard.isAlreadyProcessed(topic, key)) return;   // Redis EXISTS
...  work  ...
idempotencyGuard.markProcessed(topic, key);                    // Redis SET
```

A window exists between check and mark. **Mostly unreachable by design** — the same key hashes to
the same partition, and a partition belongs to exactly one consumer in the group, so ordinary
redelivery is *sequential*.

**But reachable during a rebalance.** If processing exceeds `max.poll.interval.ms`, Kafka assumes
the consumer died, reassigns the partition, and the new consumer starts from the last *committed*
offset — while the original is still working. Two threads, same checkout, both past the check.
And "takes too long" is exactly what happens under the lock contention this system exists for.

### 2. It protects the *message*, not the *intent*

The guard makes **redelivery of one message** safe. It does nothing about **two messages
expressing the same user intent**.

A user clicks checkout, sees nothing (→ F8), clicks again. `initiateCheckout:95` mints a **fresh
UUID**. Two keys, no duplicate detected, both proceed → two orders, two deductions. The guard is
working as designed and is powerless, because at the message layer these genuinely *are* two
distinct requests.

> **Message-level idempotency ≠ intent-level idempotency.** The second needs a *client-supplied*
> idempotency key — the Stripe pattern, where the caller generates it so a retry reuses it.

One accidental safety net: on redelivery after success the cart is already cleared, so
`loadCartSnapshot` throws "Cart is empty." The comment at `:75-76` leans on this. It's a side
effect, not a designed defense — and **F1** is what happens when it fires on a path nobody intended.

## What a stronger version looks like

**Let the database arbitrate.** An `idempotency_key` column on `orders` with a **unique index**,
written in the same transaction as the order. Atomic in a way check-then-act can't be: the second
insert fails on the constraint, you catch it and treat it as "already done." No window — the
database serializes it.

**Or claim-then-confirm in Redis:** `SET key NX` with a short TTL to *claim* (atomic, one winner),
do the work, then convert to a permanent marker. On crash the lease expires and a retry re-claims.

⚠️ You **cannot** just move the existing `SET` to the start — that reintroduces the
mark-before-work bug. The claim must be a **lease that expires**, not a permanent marker.

## Questions I should be able to answer

- Why is at-least-once the guarantee, and why are at-most-once and exactly-once both worse here?
- Give an idempotent SQL statement and a non-idempotent one. Which shape is `deductStock`?
- Why is the topic part of the dedup key? Which two ShopHub topics collide without it, and why
  would a checkout-only test miss it?
- Why 24 hours for the TTL?
- Why must the mark come after the work rather than before?
- Where is the check-then-act window, why is it *usually* unreachable, and what makes it reachable?
- Difference between message-level and intent-level idempotency. Which does the guard give you?
- Why can't you fix the race by moving the `SET` to the start of the handler?
