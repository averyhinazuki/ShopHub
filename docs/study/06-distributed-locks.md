# 06 — Distributed locks (Redisson)

Unit 6. Prerequisite: `04a` Concept 4 (read-modify-write, conditional UPDATE, `rows == 0`).

Absorbs `TECH_OVERVIEW.txt` §1c, with one correction noted at the end.

---

# Concept 1 — Why a *distributed* lock ✅

`synchronized` and `ReentrantLock` coordinate threads **inside one JVM** — the lock is an object
in that process's memory.

Run a second instance (second container, second box, blue/green overlap) and each JVM has its
**own** lock object. Thread A in process 1 acquires its lock; thread B in process 2 acquires
*its* lock. Both succeed instantly, neither aware of the other. **Mutual exclusion is gone**, and
it only breaks when you scale — the worst time to find out.

A **distributed lock** keeps the state in a shared external system every process can see. Here,
Redis via Redisson.

*Honest note:* ShopHub runs one app container today, so a per-product
`ConcurrentHashMap<Long, ReentrantLock>` would work right now. Redisson buys two things — it
survives horizontal scaling unchanged, and you don't hand-roll a lock registry keyed by product id.

---

# Concept 2 — How Redisson implements it ✅

Underneath, a lock is **just a Redis key**:

| Part | Purpose |
|---|---|
| **key** | `lock:product:7` — what's locked |
| **value** | owner id `{clientUUID}:{threadId}` + a re-entry count — *who* holds it |
| **TTL** | the **lease** — when Redis auto-deletes it |

Acquisition runs as a **Lua script** inside Redis, so "check if it exists, and if not, set it" is
one indivisible operation — the same trick as the conditional `UPDATE`: check and act must be one
step, or two clients both see "free."

**Why the value stores an owner:** it's what makes `isHeldByCurrentThread()` possible, and what
makes **reentrancy** work (same thread re-acquires, count increments, only the final `unlock()`
deletes the key).

**Why the TTL is mandatory:** a crash, a kill, or a long GC pause while holding the lock means
`unlock()` never runs. Without expiry the key sits forever and **that product can never be checked
out again.** The TTL guarantees eventual release with no human intervention.

## The two numbers in `tryLock(5, 10, TimeUnit.SECONDS)`

Used identically at `OrderService:137`, `ProductService:141`, `OrderExpiryScheduler:132`.

```java
lock.tryLock(5, 10, TimeUnit.SECONDS)
              │   └── LEASE: how long I HOLD it before Redis expires it
              └────── WAIT:  how long I BLOCK trying to get it
```

- **Wait 5s** — block up to 5s; if it frees up, take it and return `true`; if not, return
  **`false`**. It does **not** throw and does **not** wait forever. That's `tryLock` vs `lock()`.
- **Lease 10s** — Redis deletes the key after 10s **whether or not you're finished**.

## Gotchas

- **The lease can expire mid-section.** If your critical section exceeds 10s, Redis deletes the
  key, another thread acquires it, and **two threads are inside simultaneously** — neither aware.
  Then your eventual `unlock()` would delete a key that **now belongs to someone else**, evicting
  them from *their* critical section.
- Hence `if (lock.isHeldByCurrentThread()) lock.unlock();` (`ProductService:162`) is **necessary,
  not tidiness.** It compares the owner id in the Redis value against you; if the lease expired
  and someone else took it, the check fails and you skip the unlock instead of evicting them.
- **Redisson's watchdog does not apply here.** It auto-renews every 10s so a lock never expires
  under you — but only when you call `lock()` **without** a lease time. Passing an explicit lease
  **disables it.** ShopHub always passes 10s, so there is no renewal and 10s is a hard ceiling on
  every critical section.
- **The three callers handle `false` differently.** `OrderService:138-142` throws;
  `ProductService:142` throws; `OrderExpiryScheduler:134` **logs a warning and skips** — which is
  **F2**, the permanent stock leak. Same signal, three handlers, one silently discards work.

---

# Concept 3 — What the lock actually protects ✅

If `deductStock` is already race-safe, why lock at all? Check each writer **in isolation**:

| Operation | SQL | Safe alone? |
|---|---|---|
| `deductStock` (`:19-21`) | `SET available = available - :qty WHERE ... AND available >= :qty` | ✅ conditional |
| `restoreStock` (`:26-28`) | `SET available = available + :qty` | ✅ unconditional, but adding can't go negative |
| `adjustStock` (`:33-37`) | `SET available = available + :delta` | ❌ **and `delta` can be negative** |

**`adjustStock` has no `WHERE` guard.** Nothing in the statement prevents negative stock. The only
protection is a **Java** check, `ProductService:145-152`:

```java
if (delta < 0) {
    ProductInventory inv = inventoryRepository.findByProductId(productId).orElseThrow();  // READ
    if (inv.getAvailableStock() + delta < 0) throw new IllegalArgumentException(...);      // CHECK
}
inventoryRepository.adjustStock(productId, delta);                                          // WRITE
```

Read-check-write — formally a **TOCTOU** race (time-of-check to time-of-use). It can't be fixed
with a row count, because the check lives outside the statement. **The lock is what makes
`adjustInventory` correct.**

## Why checkout must take the same lock

```
                                            stock = 5
Admin:     reads 5, checks 5 + (-3) = 2 >= 0    ✓ guard passes
Checkout:  deductStock(qty=5) → 5 >= 5 ✓ → 0     ← flawless in isolation
Admin:     adjustStock(-3) → stock = -3          ← guard decided on stale data
```

**Checkout did nothing wrong.** Its conditional UPDATE prevented overselling on its own terms. It
merely invalidated the admin's guard between that guard's read and write — and no conditional
UPDATE on the checkout side can protect a check happening in another thread's Java code.

> **A lock only works if every participant takes it.** One non-participant silently breaks mutual
> exclusion for everyone else.

| Who takes `lock:product:{id}` | Where |
|---|---|
| Checkout deduction | `OrderService:135` |
| Admin inventory adjust | `ProductService:139` |
| Expiry stock restoration | `OrderExpiryScheduler:130` |

**Granularity matters:** the key includes the product id, so contention is **per product**. Two
checkouts for *different* products never block each other. A single global lock would serialize
the entire site.

`ProductService:144`'s comment — *"Guard inside the lock so it reads committed state"* — records a
deliberate fix. The guard used to sit **outside** the lock, where it was decorative.

## Correction to `TECH_OVERVIEW.txt` §1c

The old note says *"two concurrent checkouts for the same product can interleave and produce
incorrect stock values."* **That overstates it.** Two checkouts alone cannot corrupt stock —
`deductStock` is conditional, so the worst case is one gets `rows == 0` and is correctly told sold
out. And the cache steps are deletes, which are order-independent (→ `05`). The real justification
for the lock is `adjustInventory`'s TOCTOU guard, which is why checkout must participate.

## The design observation

The lock is load-bearing **only because one operation wasn't expressed conditionally.** Make
`adjustStock` conditional —

```sql
WHERE pi.product.id = :productId AND pi.availableStock + :delta >= 0
```

— and the guard becomes atomic, returns a row count, and the lock stops being required for
*correctness* on this path.

That matters beyond elegance, because a guard in a service method protects only the callers who go
through that method. Add a bulk write-off tool, an admin script, or a data migration that calls the
repository directly and the invariant is silently gone. Note also that `adjustStock` is
unconditional enough to produce negative stock **with no concurrency at all** — `adjustStock(7, -100)`
against 5 in stock gives **-95**, atomically. **Atomic ≠ correct**: atomicity guarantees the
statement completes indivisibly, not that the result is legal.

> A check in application code protects the callers you thought of.
> A check in the database protects the callers you didn't.

Finally, current correctness **depends on the lease never expiring mid-section**. Two queries in
10 seconds is generous, but if it ever exceeded the lease another thread could enter and the
negative-stock path is live again.

## Questions I should be able to answer

- Why doesn't `synchronized` work here, and when exactly would it start failing?
- What are the three parts of a Redisson lock in Redis, and why does the value store an owner?
- Why is a TTL on the lock mandatory rather than optional?
- Distinguish the two numbers in `tryLock(5, 10, SECONDS)`. What is returned on failure?
- What two things go wrong if a critical section outlives its lease?
- Why is `isHeldByCurrentThread()` necessary rather than defensive?
- When does Redisson's watchdog apply, and why doesn't it here?
- Which of the three stock operations is unsafe alone, and why?
- Why must checkout take the lock when its own deduction is already race-safe?
- Why is the lock key per-product rather than global?
- Why is "it's a single atomic SQL statement" not the same as "it's correct"?
