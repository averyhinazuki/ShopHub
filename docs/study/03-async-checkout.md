# 03 — The async checkout split

Unit 3. Covers: why checkout is asynchronous, what `202 Accepted` actually promises, and how
the status channel works — including the two places it's broken.

Prerequisite: `02` Concept 3 (`SecurityContextHolder` is a `ThreadLocal` and does not cross
threads). That fact is *why* `processCheckout` takes a `userId` parameter.

---

# Concept 1 — Async request handling and the `202` contract ✅

## What problem it solves

The synchronous design — validate, lock, deduct, persist, return `201` — is simple and tells
the client the outcome immediately. It breaks under a spike.

Tomcat's thread pool is bounded (~200, → `01`). A checkout takes a distributed lock per
product plus several DB round-trips; call it 300ms under load. That's **~660 checkouts/sec as
a hard ceiling**, and everything beyond queues or is refused.

Worse in a flash sale: 10,000 people hit the same product in one second, so all those threads
pile onto the **same** Redisson lock, serializing while holding threads *and* DB connections.
The pool exhausts and unrelated endpoints — browsing, login — start failing too, because they
draw from the same pool. **One hot product takes down the whole site.**

## How it works

Separate *accepting* work from *doing* it:

1. The request thread does only cheap validation — "is this legitimate?"
2. It writes the work to a **queue** and returns a **receipt**.
3. A separate worker pool drains the queue at whatever rate the DB actually sustains.
4. The client uses the receipt to learn the outcome later.

The thread is freed in milliseconds, so the ceiling becomes "how fast can we *accept*" rather
than "how fast can we *complete*." The queue absorbs the spike; workers apply natural
backpressure by not going faster than they can.

## `202 Accepted` is a two-sided contract

| The server promises | The server does **not** promise |
|---|---|
| Received and looks valid | That it succeeded |
| Queued for processing | That it will succeed |
| Here's an id to track it | That it has happened *yet* |

Contrast `201 Created`, which asserts the resource **now exists**. `202` asserts only that
someone will try. So the second half binds the **client**: a `202` *obligates* a follow-up. A
client treating `202` as "done" has broken the protocol — handed a claim ticket and thrown it
away.

You need three things to make it work: a **correlation id**, a place to **record the outcome**,
and a way for the client to **learn** it. On the last:

- **Polling** — client repeatedly asks. Trivial, works everywhere, costs N wasted requests.
- **Push** (WebSocket/SSE) — server notifies. Efficient and instant, but the connection is
  persistent and stateful, complicating load balancing and reconnects.

## Where it is in ShopHub

`OrderController:25-28` returns `HttpStatus.ACCEPTED`. `OrderService.initiateCheckout:85-118`,
all on the **HTTP thread**:

1. `:86` resolve user; `:88-93` load cart, reject if empty — cheap validation only
2. `:95` mint `checkoutId` = `UUID.randomUUID()` — **correlation id**
3. `:102-105` write `checkout:{id}` → `{status: PENDING}`, TTL 30 min — **status record**
4. `:110-114` publish `CheckoutRequestedEvent` — **the queue**
5. `:117` return `PENDING`; thread freed. No locks taken, no stock touched.

Then on a **Kafka listener thread**, `CheckoutRequestedConsumer` calls
`processCheckout(event.getUserId())` — locks, deductions, `persistOrder`. The userId travels
**inside the message** because the thread-local doesn't cross threads (→ `02`).

Read side: `OrderController:31-34` → `getCheckoutStatus` reads the Redis key.

Server side of the contract: correlation id ✅, status record ✅, queue ✅, status endpoint ✅.

---

# Concept 2 — The status channel and the cost of "unknown" ✅

## Why Redis rather than a MySQL table

| | MySQL table | Redis key |
|---|---|---|
| Expiry | needs a cleanup job you write, schedule, monitor | **TTL native** — self-deletes |
| Read cost under polling | query + a connection from the bounded pool | one in-memory GET |
| Durability | survives restart | lost on flush/restart |
| Schema | migration | none |

The two decisive reasons — note that raw speed alone wouldn't justify it:

1. **TTL is native.** The data's whole purpose expires; MySQL would need a moving part for that.
2. **The read pattern, not the write.** Polling makes *many* reads per checkout. In MySQL each
   takes a connection from the same pool `persistOrder` needs — so under a flash sale the
   polling would starve the checkout it's asking about. Redis reads sit outside that pool.

Third: **non-durability is acceptable** because the durable record is the `orders` row. This key
is a notification, not the truth.

## One identifier, three jobs

- `OrderService:103` — the **Redis key** (`"checkout:" + checkoutId`)
- `OrderEventProducer:77` — the **Kafka message key**, which determines the **partition**, so
  all messages for one checkoutId land on one partition and are handled in order
- `CheckoutRequestedConsumer:54` — the **idempotency key**, via `@Header(RECEIVED_KEY)`

One UUID, three roles, no extra plumbing.

## Gotchas

- **Absent ≠ pending.** `getCheckoutStatus:185-187` synthesizes `PENDING` when the key is
  missing. Absence has four causes and only one is pending (queued / never existed / expired /
  Redis flushed). **There is no elapsed time at which the API says anything else** — at 31
  minutes the key is gone, the fallback fires, and it answers `PENDING` forever. An infinite
  loop encoded in the API contract. (→ F9)
- **The 30-minute TTL is not the bug.** It's correct memory hygiene — without it Redis
  accumulates a key per checkout until it OOMs. The bug is the *read path* treating absence as
  an ongoing state rather than as absence of information.
- **The Kafka send is fire-and-forget.** `OrderEventProducer:79-89`'s `whenComplete` only
  *logs* on failure; `JsonProcessingException` at `:90-92` is swallowed the same way. A broker
  outage loses the message silently.
- **Write ordering is backwards.** `:102` writes `PENDING` *before* the send is known to have
  succeeded, and `202` returns regardless — so a status record can exist for work never queued.
- **The frontend never polls at all.** `CartView.vue:112-124` discards the response, resets the
  cart, and navigates. `checkout-status` appears nowhere in `frontend/src/`. axios resolves on
  2xx so `202` takes the happy path; failures are silent, retries can double-order, and even a
  *successful* checkout shows an empty order list because the fetch beats the consumer. (→ F8)

## The state machine it should have

| Response | Meaning | Client action |
|---|---|---|
| 200 + `PENDING` | genuinely queued | keep polling |
| 200 + `SUCCESS` | done, has `orderId` | stop, navigate |
| 200 + `FAILED` | done, has `failureReason` | stop, show it |
| **404** | unknown or expired | **stop**, suggest checking orders |

Every path terminates. Achieved by throwing `ResourceNotFoundException` instead of synthesizing
`PENDING`. The client should *also* bound its own polling (max attempts + backoff) rather than
trusting the server to eventually say something.

---

# Concept 3 — The full end-to-end trace ✅

## Kafka vocabulary (the minimum to follow the trace)

**Kafka is an append-only log, not a queue.** Everything follows from that.

- **Topic** — a named log. Three here (`KafkaTopicConfig:11-13`): `checkout-requested`,
  `order-created`, `payment-completed`.
- **Partition** — each topic splits into N independently-ordered logs. All three use **3**
  (`:20`, `:28`, `:36`). **Ordering holds within a partition, never across.**
- **The message key picks the partition** — `hash(key) % 3`. Same key → same partition → those
  messages are strictly ordered relative to each other. Different keys → no ordering relationship.
- **Offset** — position in a partition, assigned on append.
- **Consumer group** — consumers sharing work; each partition goes to **exactly one** consumer in
  the group. One group here, `flash-sale-group` (`application.yml:41`). So 3 partitions ⇒ at most
  **3 parallel consumers**; a 4th idles.
- **Reading does not remove.** The message stays for the retention period; a consumer only
  advances its **committed offset** (stored per `group`+`partition`). Hence replay is possible —
  and so is double delivery.

Because the offset is committed **after** processing, a crash in between causes redelivery. That
is **at-least-once**, and it's why every consumer must be idempotent.

## The map — one click, five threads

```
THREAD 1  http-nio-8080-exec-N (Tomcat)
  browser → nginx → JwtFilter → OrderController → initiateCheckout
  validate · mint UUID · Redis PENDING · hand to producer · return 202 · thread released
       │
THREAD 2  kafka-producer-network-thread
  serialize · partition = hash(checkoutId) % 3 · batch · send
       │
     BROKER   topic checkout-requested, partition 1
              ... offset 47 ─ 48 ─ [49 ← ours]
       │  consumer polls
THREAD 3  KafkaListenerEndpointContainer#0-0-C-1
  dedup check · deserialize · processCheckout(userId)
     ├─ loadCartSnapshot              [tx 1, readOnly]
     ├─ per product: lock → deductStock → unlock   [tx per UPDATE]
     └─ persistOrder                  [tx 2] → publishes in-process domain event
          └─ AFTER_COMMIT → bridge → send order-created ──┐
  Redis SUCCESS + orderId · markProcessed · commit offset  │
       │                                                   │
THREAD 4  cacheEvict-N                    THREAD 5  ...#1-0-C-1
  sleep 500ms → delete product cache        order-created consumer → MongoDB
```

## Stage by stage

| # | Thread | What happens | Where |
|---|---|---|---|
| 1 | Tomcat | nginx → filter chain; `JwtFilter` sets the thread-local `Authentication`; `AuthorizationFilter` passes | `OrderController:26` |
| 2 | Tomcat | **Cheap validation only** — resolve user, load cart, reject if empty. **No locks, no stock, no writes** | `initiateCheckout:85-93` |
| 3 | Tomcat | Mint `checkoutId` = `UUID.randomUUID()` | `:95` |
| 4 | Tomcat | `SET checkout:{id} = PENDING`, TTL 30 min | `:102-105` |
| 5 | Tomcat | `kafkaTemplate.send(topic, key=checkoutId, payload)` — **returns immediately**, future + logging callback | `:110-114`, `OrderEventProducer:73-93` |
| 6 | Tomcat | Return `202`; context cleared; thread returned to pool. **A few ms, 2 Redis ops, 2 SELECTs** | `OrderController:27` |
| 7 | producer net | Append to in-memory **batch buffer**; background thread drains batches (why producers are fast); compute partition; `StringSerializer` | `application.yml:46-48` |
| 8 | broker | **Append to tail** of partition log, assign offset. Durable (`replicas(1)` — single broker, no redundancy). Persists whether or not read | — |
| 9 | listener | Long-poll returns the record; `@Header(RECEIVED_KEY)` delivers the checkoutId. **No `SecurityContext` on this thread** | `CheckoutRequestedConsumer:46-50` |
| 10 | listener | Dedup: Redis `hasKey kafka:processed:checkout-requested:{id}`, 24h TTL (outlasts any retry window) | `:52-55`, `ConsumerIdempotencyGuard:24-26` |
| 11 | listener | Deserialize. Malformed JSON **returns early** — a poison pill would otherwise retry forever, since a parse failure is permanent | `:57-63` |
| 12 | listener | `processCheckout(userId)`: `loadCartSnapshot` (readOnly tx, resolves lazy products, **snapshots prices**) → deduction loop → `persistOrder` | `:69` → `OrderService:129-177` |
| 13 | listener | `persistOrder` publishes an **in-process** `ApplicationEvent`, not a Kafka message | `OrderService:226-227`, `:253-254` |
| 14 | listener | `@TransactionalEventListener(AFTER_COMMIT)` forwards to Kafka **only if the tx committed** | `OrderEventKafkaBridge:28-38` |
| 15 | listener | Redis ← `SUCCESS` + orderId, **then** `markProcessed`, **then** offset commits. Side effects first, bookmark last | `:70-77` (why, at `:75-76`) |
| 16 | cacheEvict | sleep 500ms → second cache delete (→ `05`) | `ProductCacheService:86` |
| 17 | listener 2 | `order-created` consumer → `OrderActivityLog` in MongoDB, `event: "ORDER_CREATED"`. Keyed on **orderId**, not checkoutId | `OrderEventConsumer:40-65` |

## The AFTER_COMMIT bridge — why it's the most elegant piece here

`persistOrder` publishes a Spring `ApplicationEvent`; `OrderEventKafkaBridge` re-publishes it to
Kafka only after commit. Publishing straight to Kafka inside the transaction would mean a later
rollback leaves an `order-created` event announcing an order that doesn't exist — a **phantom
event**, and **unretractable because Kafka is append-only**. The two-layer design makes that
structurally impossible.

Acknowledged residual gap (`:18-19`): a JVM crash *between* commit and send loses the event.
Closing it needs the **transactional outbox** — write the event to a DB table in the same
transaction, with a separate publisher draining it.

## The three branches

- **Sold out** → `deductStock` (`ProductInventoryRepository:19-21`) matches 0 rows →
  `SoldOutException` (`OrderService:145-148`) → **terminal**: write `FAILED`, mark processed,
  **don't rethrow** (`CheckoutRequestedConsumer:81-89`). Retrying can never succeed.
- **Transient** (DB down, lock timeout) → rethrow (`:91-96`, comment at `:92`) →
  `@RetryableTopic(attempts="4", backoff=@Backoff(delay=1000, multiplier=2.0))` (`:46`) republishes
  to an auto-created **retry topic**, returning after ~1s/2s/4s. Retries live on a *separate
  topic*, so a slow-failing message **doesn't block the main partition behind it** — no
  head-of-line blocking.
- **Retries exhausted** → **dead letter topic**; `@DltHandler` writes `FAILED` (`:99-124`), guarding
  against overwriting a `SUCCESS` (`:104-113`). Nothing is silently dropped.

**Note — `deductStock` has its own `@Transactional`** (`ProductInventoryRepository:17`, comment at
`:15-16`): *"commits its own short tx so the deduction is durable before the lock releases."* Each
deduction commits **inside** the lock, so durable state is settled before another thread can
acquire it. Load-bearing for `07`.

## What each choice buys

| Choice | Buys |
|---|---|
| 202 + queue | Tomcat threads freed in ms; the queue absorbs the spike, not the thread pool |
| 3 partitions | Up to 3 parallel consumers, ordering preserved per checkout |
| `checkoutId` as message key | One partition per checkout → ordered redelivery + a natural dedup key |
| Dedup guard | Makes at-least-once safe |
| Mark processed **after** side effects | Crash mid-processing retries instead of silently dropping |
| Poison-pill early return | An unparseable message can't retry forever |
| `SoldOutException` terminal | No pointless retries on a permanent business outcome |
| Retry on a separate topic | No head-of-line blocking |
| AFTER_COMMIT bridge | A rolled-back tx can never emit a phantom event |
| Mongo audit on its own consumer | Analytics can't slow or fail a checkout |

## Questions I should be able to answer

- Why is Kafka a log rather than a queue, and what does that make possible?
- What does the message key determine, and what ordering guarantee follows?
- 3 partitions, one consumer group — what's the maximum useful parallelism?
- Why is at-least-once the natural consequence of committing offsets after processing?
- Name the five threads a single checkout touches, and what each does.
- Why must `userId` travel inside the message?
- Why does `persistOrder` publish an in-process event instead of sending to Kafka directly?
- Why is `SoldOutException` terminal while a DB failure is retried?
- Why do retries go to a separate topic rather than being redelivered in place?
- What does the transactional outbox pattern fix that AFTER_COMMIT doesn't?
- Why does malformed JSON return early instead of throwing?

---

## Earlier concept questions

- Why does a synchronous checkout let one hot product take down unrelated endpoints?
- What does `202` promise, what does it not, and what obligation does it put on the client?
- What three pieces do you need to implement an async accept/poll flow?
- Polling vs push — the tradeoff in one sentence each.
- Two strongest reasons the status lives in Redis and not MySQL. Why isn't "it's fast" the answer?
- What three jobs does `checkoutId` do? What does the Kafka message key determine?
- Broker is down and the client polls correctly: what does it see at 1s, 1min, 31min? Where does
  it end?
- What is the 30-minute TTL actually for, and why isn't it the bug?
- Minimum change to make the status endpoint's state machine terminate?
