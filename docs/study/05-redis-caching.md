# 05 — Caching with Redis

Unit 5. Prerequisite: `04a` (especially transactions and the `COMMIT` visibility boundary) —
the double-deletion race is not explainable without it.

Absorbs `TECH_OVERVIEW.txt` §1a–1b, which this supersedes.

---

# Concept 1 — Why cache, and cache-aside ✅

## What problem it solves

Product pages are the most-read thing in a shop, product data changes rarely, and every view runs
the same queries (`getProduct` needs two — the product and its inventory).

The obvious cost is latency: MySQL ~1–10ms vs a Redis `GET` ~0.1ms. **The expensive cost is the
connection pool.** `application.yml:14` sets `maximum-pool-size: 50` — fifty connections for the
whole application, shared with the ones checkout needs for `deductStock` and `persistOrder`. In a
flash sale, browsers of the hot product can starve the checkouts you're trying to serve.

> **A cache hit uses zero database connections.** That's the real win — not speed, but not
> consuming the scarcest resource you have.

## The pattern

Cache-aside (a.k.a. lazy loading). The **application** manages the cache.

```
READ:   check cache
          ├─ HIT  → return. No database involved.
          └─ MISS → query DB → write result into cache → return

WRITE:  write to DB  →  DELETE the cache entry
```

**The defining property: the cache is not in the data path.** MySQL is the truth; Redis is an
optimization that could be wiped entirely with zero data loss. That's what makes cache-aside the
most common pattern — the cache is *allowed* to fail.

(Alternatives: *write-through* updates cache and DB together on write; *read-through* has the
cache library fetch on miss. Both put the cache in the data path.)

## Where it is in ShopHub

`ProductService.getProduct:59-72` is textbook cache-aside — return on hit, else two queries,
`setDetail`, return.

Two keys, both TTL 60s (`ProductCacheService:32-34`):

| Key | Holds | Read by |
|---|---|---|
| `product:{id}:detail` | full `ProductResponse` JSON | `getDetail` ✅ |
| `product:{id}:stock` | `availableStock` as a string | `getStock` — **never called** ❌ (→ F11) |

**List endpoints are deliberately NOT cached.** Only single-product detail. Caching paginated
lists would mean invalidating every page of every filter combination on any write — the
invalidation problem multiplies. Skipping it is a deliberate scope decision, not an oversight.

## Why a TTL *and* explicit invalidation?

Looks redundant — if every write deletes the entry, why also expire after 60s?

**The TTL is a safety net for invalidation failures.** Explicit invalidation is code, and code has
gaps: Redis briefly unreachable when the delete fires; the process dying between commit and
delete; a new write path that forgets to invalidate; a row changed by a migration or a manual
`UPDATE`. In each case you hold a **permanently** stale entry with nothing to correct it.

> Explicit invalidation makes the cache **fresh**. The TTL makes staleness **survivable**. The TTL
> length is your answer to "how long can I tolerate being wrong if invalidation fails?"

---

# Concept 2 — The delayed double deletion ✅

## The race a single delete cannot cover

```
T0   Writer  deleteCache(7)                     cache: empty
T1   Reader  getDetail(7) → MISS                cache: empty
T2   Reader  SELECT FROM products WHERE id=7    ← reads the OLD value
T3   Writer  UPDATE ... COMMIT                  ← new value becomes the truth
T4   Reader  setDetail(7, oldValue)             cache: STALE for up to 60s
```

**Nothing misbehaves.** At T2 the writer had run its `UPDATE` but **not committed**, and
uncommitted changes are invisible to other connections (→ `04a`). So the old price was *the only
committed value that existed* for the reader's connection. It read correctly.

The defect lives entirely in the **gap**: the value was true when read (T2) and false when stored
(T4), and nothing in the reader's world could have known that. The first deletion at T0 is
powerless — it fired before the reader had even started. **You cannot delete a poisoned entry that
doesn't exist yet.**

## The fix

```
T0        Writer  deleteCache(7)          ← first deletion
T3        Writer  COMMIT
T4        Reader  setDetail(7, old)       ← poisons the cache
T0+500ms  Writer  deleteCache(7)          ← SECOND DELETION sweeps the poison
```

**Why ~500ms?** It must comfortably exceed the gap between a reader's `SELECT` and its cache
write, so any in-flight reader has finished poisoning before you sweep. Too short and you sweep
before the poison lands; too long and you leave stale data readable for no reason.

## Why DELETE rather than overwrite with the new value

The decisive reason is **concurrent writers**:

```
A: UPDATE db → 100
B: UPDATE db → 200      ← DB truth is 200
B: SET cache = 200
A: SET cache = 100      ← A's cache write lands last
```

DB says 200, cache says 100, wrong until the TTL. The trap: **cache write order is completely
independent of database commit order** — different systems, different latencies, nothing
coordinating them. A writer that committed *first* can still touch the cache *last*.

With deletes, any interleaving leaves the cache **empty**, and the next reader loads 200.

> **Deletion is idempotent and order-independent** — any sequence of deletes yields the same
> state. Writes are order-dependent and you don't control the order.

The invariant this buys: the cache is only ever *absent* or *populated from the source of truth* —
never *holding whatever some writer decided*.

(Note "a delete could fail too" is **not** the distinguishing argument — both a failed delete and
a failed overwrite leave a bad entry that the TTL heals. Interleaving is the real reason.)

## Where it is in ShopHub

Checkout, `OrderService:143-151`:

```java
cacheService.deleteCache(item.productId());            // :143  first deletion
int rows = inventoryRepository.deductStock(...);       // :144  commits its own tx
lock.unlock();                                          // :150
cacheService.scheduleSecondDeletion(item.productId()); // :151  second deletion, +500ms
```

Admin edits do the same at `ProductService:154` (`// first deletion, before the write`) and `:166`.

`ProductCacheService` **must** be a separate bean from `ProductService` so Spring's proxy applies
`@Async` — an intra-bean self-call bypasses it and the asynchrony silently vanishes (→ `04`
Concept 3). Its class comment at `:24` says exactly this.

## Gotchas

- **The mechanism is sound; its scheduling fails under load.** `scheduleSecondDeletion` holds a
  pool thread on `Thread.sleep(500)`. With core 8 that's ~16 second-deletions/sec for the whole
  app, and the 2000-deep queue means the pool never grows toward its max of 32 — it queues.
  Under load, deletions fire **minutes** late, evicting long-since-correct entries while the
  actual stale window went uncovered. (→ **F3**) Fix: `ScheduledExecutorService.schedule(...)`,
  which costs no thread during the delay.
- **A Redis outage returns 500, it doesn't degrade.** `getDetail`/`setDetail` catch only
  `JsonProcessingException`; `deleteCache` catches nothing. `RedisConnectionFailureException`
  propagates to `GlobalExceptionHandler:51`. Cache-aside *promises* the cache may fail — but
  graceful degradation isn't automatic, you have to write the fallback. (→ **F12**)
- **`product:{id}:stock` has no reader**, yet is written and deleted on every cache operation —
  double the Redis round trips for nothing, and redundant with the detail JSON. (→ **F11**)

## Questions I should be able to answer

- Why is the connection pool, not latency, the main argument for caching here?
- What are the read and write paths of cache-aside, and what makes the cache "not in the data path"?
- Why are list endpoints deliberately not cached?
- Why have a TTL when every write already invalidates explicitly?
- In the double-deletion timeline, why was the reader's stale `SELECT` *correct behaviour*?
- Why can't the first deletion cover that race?
- Why delete rather than overwrite? (Give the concurrent-writer interleaving, not "a write might
  fail.")
- What single property makes deletion safe under any interleaving?
- Why must `ProductCacheService` be its own bean?
- Under load, why does the second deletion stop doing its job?
