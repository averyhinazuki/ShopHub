# 09 — Order lifecycle and exactly-one-wins races

Unit 9. Prerequisites: `04a` (conditional UPDATE, `rows == 0`), `06` (the lock and when it's
needed), `07` (compensation).

---

# Concept 1 — Exactly one winner, with no lock at all ✅

## The state machine

```
                    ┌─── /pay ────────────→  PAID       (terminal)
   PENDING ─────────┤
                    └─── expiry scheduler →  CANCELLED  (terminal)
```

Both transitions start from `PENDING`. An HTTP request and a background job can attempt them in
the same millisecond. Exactly one must win, and the loser must find out.

## The mechanism

`OrderRepository:26-36` — both are conditional UPDATEs guarded on the current state:

```sql
UPDATE orders SET status='PAID', paid_at=:now WHERE id=:id AND status='PENDING'   -- payIfPending
UPDATE orders SET status='CANCELLED'          WHERE id=:id AND status='PENDING'   -- cancelIfPending
```

Same row, so InnoDB serialises them. The first takes the row lock, evaluates the condition against
the committed value, writes, commits → `rows = 1`. The second blocks, resumes, **re-reads** (now
`PAID` or `CANCELLED`), matches nothing → `rows = 0`.

## What isn't here: any application lock

**No Redisson lock on this path at all** — a deliberate contrast with inventory.

Inventory needed one *not* because `deductStock` was unsafe, but because `adjustInventory` performs
its guard in **Java**: read stock, decide, write, with a gap no SQL condition can close.

Here every transition carries its condition in the `WHERE` clause, so the **database is the
arbiter**. No lock to acquire, no lease to expire, no `isHeldByCurrentThread`, no failure mode
where the lock desynchronises from the data.

> You need a lock when some participant checks a condition **outside** the statement that acts on
> it. The number of participants is irrelevant — where the check lives is everything.

(Note this path has *more* contenders than inventory — `/pay`, the scheduler, and other scheduler
instances — and still needs no lock.)

## What the loser experiences

| Loser | Code | Result |
|---|---|---|
| `/pay` | `OrderService:282-286` → `IllegalStateException` | **409 Conflict** (`GlobalExceptionHandler:37`) |
| Scheduler | `OrderExpiryScheduler:88-91` → debug log, `return` | silently skips |

Both correct, for different reasons. `409` means "well-formed request, but the resource is no
longer in the required state" — precisely accurate. And the scheduler skipping is right: if `/pay`
won, the order was legitimately paid and its stock legitimately sold, so restoring it would be the
bug (`:86` says so).

---

# Concept 2 — The scheduler ✅

```java
@Scheduled(fixedDelayString = "#{${app.order.expiry-job-interval-seconds} * 1000}",   // 60s
           initialDelayString = "30000")
```

Config (`application.yml:66-68`): timeout 15 min, interval 60s, batch 100.

- **`fixedDelay`, not `fixedRate`** — measures from the *end* of the previous run, so runs can
  never overlap within a JVM. `fixedRate` measures from the start, so a 90-second run would
  overlap the next.
- **`initialDelay` 30s** — don't scan during startup while connections are still warming.
- **`LIMIT 100`** — bounded batch; a 50,000-order backlog drains 100/minute instead of loading
  one enormous list.
- **Per-order failure isolation** (`:74-79`) — one bad order can't abort the batch.

## The accidental superpower: already multi-instance safe

`@Scheduled` coordinates nothing across processes. Run three instances and all three scan and find
the same expired orders. That would normally demand a distributed lock on the job — and doesn't:

```
Instance A:  cancelIfPending(42) → rows = 1  → proceeds to restore
Instance B:  cancelIfPending(42) → rows = 0  → skips
Instance C:  cancelIfPending(42) → rows = 0  → skips
```

**The same conditional UPDATE arbitrates `/pay` vs scheduler *and* scheduler vs scheduler.** One
pattern, three racing parties, no coordination primitive anywhere. (All instances still perform the
scan — wasted work, never duplicated action.)

## Gotcha — the scan is a full table scan, every minute, forever

```sql
SELECT o.id FROM orders WHERE o.status = 'PENDING' AND o.created_at < ?  LIMIT 100
```

`orders` has **only `PRIMARY KEY (id)`** — no index on `status`, none on `created_at`. So MySQL
scans the whole table every 60 seconds. `LIMIT 100` doesn't help: in steady state there are
usually *zero* expired orders, so it scans every row, finds nothing, repeats.

Worse than the `getMyOrders` cost, which is only paid on user action. This runs **on a timer
regardless of traffic** and grows with total orders ever placed. (→ **F10**, extended)

---

# Concept 3 — F2, and why "deliberate" isn't the end of it ✅

After a successful cancel:

```java
int rows = orderRepository.cancelIfPending(orderId);   // :87  COMMITS IMMEDIATELY
if (rows == 0) return;
...
restoreStockForItem(orderId, productId, qty);          // :106  happens after
```

Not atomic — the compensation shape from `07`. The order is `CANCELLED` and committed before any
stock returns, and there are **two** ways the second half never happens:

- `tryLock` times out → `log.warn` and `return` (`:133-137`)
- `restoreStock` throws → catch, unlock, `log.error` (`:152-155`)

## The code documents it as intentional

`:126-127`:

> *"If the lock can't be acquired the item is skipped and its stock is not restored — a retry queue
> would close this gap; left out here by design."*

This is **not an oversight.** Someone identified the gap, judged a retry queue out of scope, and
recorded the decision. That's legitimate, and it's a much stronger position than having missed it.

## But the resulting state is real

| | |
|---|---|
| Order 42 | **`CANCELLED`**, committed, permanent |
| That item's stock | **not restored**, permanently short |
| Evidence | one `log.warn` line |

Order and inventory **actively disagree** — the order claims those units were released; inventory
still has them deducted. And it is **per item**: the loop at `:102-107` restores each line under
its own lock, so one timeout in a three-item order produces a **partially restored cancellation**.

## The actual critique

A knowingly-accepted gap with **no metric, no alert, and no reconciliation** is one you can never
learn you have hit. You'd discover it when someone notices a product reading sold-out while sitting
in the warehouse — by which point you can't reconstruct how much was lost or when.

> Accepting a known gap is fine. Accepting it *without detection* also gives up the ability to find
> out whether it mattered.

Cheapest fix isn't the retry queue — it's a counter metric on the warn path plus an alert rule.
The durable fix is the transactional-outbox shape, same as **F13**, its twin on the checkout side.

## Questions I should be able to answer

- Draw the order state machine. Which transitions are contended, and by whom?
- What decides the winner between `/pay` and the expiry job?
- Why is there no Redisson lock here when inventory needed one? State the rule.
- What does each loser see, and why is `409` the semantically right code?
- Why `fixedDelay` rather than `fixedRate`? What does `initialDelay` avoid?
- Why is the scheduler safe on three instances with no distributed lock?
- Why doesn't `LIMIT 100` rescue the expiry scan?
- After a lock timeout during restoration, what is true of the order, the stock, and the evidence?
- Why is partial restoration harder to detect than total failure?
- What separates "an acceptable known gap" from this one?
