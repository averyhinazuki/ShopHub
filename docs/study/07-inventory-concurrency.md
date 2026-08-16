# 07 — Inventory under contention

Unit 7. Prerequisites: `04a` (transactions, conditional UPDATE), `05` (cache deletion),
`06` (the lock and what it protects).

This unit is where those three converge. Read `OrderService:129-177` alongside it.

---

# Concept 1 — Why there is no outer transaction ✅

`processCheckout` deducts stock for every item, then creates the order. Obviously one logical
operation — so why isn't it one transaction? The comment at `:125-127` says it's intentional.
Here's the mechanism.

## The ordering that matters

```java
lock.tryLock(5, 10, SECONDS);                    // :137  acquire Redis lock
cacheService.deleteCache(item.productId());      // :143
int rows = inventoryRepository.deductStock(...); // :144  ← COMMITS HERE, inside the lock
if (rows == 0) { lock.unlock(); throw ... }
deducted.add(item);
lock.unlock();                                   // :150  release Redis lock
```

`deductStock` carries **its own** `@Transactional` (`ProductInventoryRepository:17`) —
*"commits its own short tx so the deduction is durable before the lock releases."* By line 150
the new value is committed and visible to everyone.

## What adding `@Transactional` would break

Spring's default propagation is `REQUIRED`: an inner `@Transactional` **joins** the outer one
rather than starting its own. So `deductStock` would stop committing at `:144` and instead commit
at the very end, after `persistOrder`.

```
Thread A: acquires lock:product:7
Thread A: deductStock → 5 → 0, UNCOMMITTED
Thread A: releases lock:product:7        ← lock free, DB change not yet visible
Thread B: acquires lock:product:7        ← succeeds immediately
Thread B: deductStock → blocks on the InnoDB row lock A still holds
          ...or, if A rolls back, B reasoned about a value that never existed
```

**The Redis lock and the database row lock fall out of sync.** Redis says "free" while the row is
still held by an uncommitted transaction. The lock's guarantee — that whoever holds it sees and
leaves behind committed state — evaporates.

Second cost: the row lock would be held from the first deduction through `persistOrder`, so every
competing checkout blocks on the database **while holding a Hikari connection**. With 50
connections total, that exhausts the pool during exactly the spike this design exists to survive.

> Short transactions **inside** the lock, not one long transaction **around** it. The lock's
> guarantee depends on committing before you release.

## What you give up

Atomicity across the whole checkout. Once deduction #1 commits it is **permanent** — no rollback
reaches it. You cannot have both: commit early (lock works, no atomicity) or commit late
(atomicity, lock broken). ShopHub picks the first and pays for it with compensation.

---

# Concept 2 — Compensation ✅

## The pattern

When you can't roll back, **undo forward** — run a new operation reversing the committed one.

```java
List<CheckoutItem> deducted = new ArrayList<>();   // :131  the undo log
try {
    for (...) { ...; deducted.add(item); }         // :149  only AFTER rows != 0
    return self.persistOrder(...);                 // :162
} catch (Exception ex) {                           // :163
    for (CheckoutItem item : deducted) {           // :164
        try {
            cacheService.deleteCache(item.productId());
            inventoryRepository.restoreStock(item.productId(), item.qty());
            cacheService.scheduleSecondDeletion(item.productId());
        } catch (Exception compensationEx) {
            log.error("[Checkout][Compensation] FAILED to restore ...");   // :171
        }
    }
    throw ex;                                      // :175
}
```

This is a **saga** in miniature: a sequence of local transactions, each committed immediately,
each with a compensating action for when a later step fails. The `deducted` list is a hand-rolled
**undo log**.

## Compensation is not rollback — three differences

**1. The intermediate state was visible.** Rollback erases history; nobody saw it. Compensation
*adds an opposite action*, so stock genuinely was lower in between. Another customer could have
been told "sold out" during that window for an item that came back. Not a bug — the honest cost of
the design.

**2. Compensation itself can fail** (`:170-173` only logs). → **F13**.

**3. Restores are commutative.** `restoreStock` is `SET available = available + :qty` —
unconditional, so any order converges. Deliberate: a *conditional* restore could fail and leave
compensation half-done.

> **Rollback: nothing ever occurred. Compensation: it occurred, then the opposite occurred.**
> Same end state, completely different reliability — a rollback cannot partially fail.

## What is NOT compensated

- **Cache deletions** — harmless, deletion is idempotent (→ `05`)
- **The order row** — doesn't need it. `persistOrder` **is** atomic internally: order, items and
  cart clear all succeed or all fail together. Compensation only handles deductions that happened
  *outside* it.

---

# Concept 3 — The three traces ✅

## Trace A — item 3 is sold out

1. Items 1, 2 deduct and commit; both appended to `deducted`.
2. Item 3: `deductStock` returns `rows == 0` → `lock.unlock()` (`:146`) → `SoldOutException`
   (`:147`).
3. Inner `catch (RuntimeException)` at `:157` checks `isHeldByCurrentThread()` — already
   unlocked, so no double-unlock — and rethrows.
4. Outer catch at `:163` restores items 1 and 2, then rethrows.
5. `CheckoutRequestedConsumer:81-89` treats `SoldOutException` as **terminal**: writes `FAILED`
   with the reason, marks processed, does **not** retry. Correct — retrying cannot create stock.
6. Customer should see "sold out"... except **F8**, the frontend never polls, so they see nothing.

## Trace B — all deduct, then `persistOrder` throws

| Table | Final state | Why |
|---|---|---|
| `orders`, `order_items` | empty | `persistOrder` threw → **its own** transaction rolled back |
| `cart_items` | **still full** | `deleteByCartId` was inside that same rolled-back transaction |
| `product_inventory` | back to original | deducted 3x, then **restored 3x by compensation** |

Inventory reads as unchanged — **but not because nothing happened.** Something happened three
times and was undone three times. The distinction is everything, because compensation can fail.

## Trace C — a `restoreStock` fails during compensation

Item 2's stock is **permanently short**. The database believes fewer units exist than physically
do; nothing self-heals; repeated occurrences shrink inventory monotonically — products reading
"sold out" while sitting on the shelf.

**Detection today: essentially none.** One log line at `:171`, unwatched, with no alert, metric,
or counter. A binlog replay would show a deduction with no matching restoration — but that is
indistinguishable from a legitimate sale, so it detects nothing by itself.

What *would* detect it: **reconciliation** — periodically assert
`total_stock == available_stock + units held by PENDING orders`. Any drift means a lost restore.

What would prevent it: **durable compensation** — write a "restore owed" row in the same
transaction as the deduction and drain it from a retry job. The **transactional outbox** again,
same answer as Unit 3's lost-event problem.

→ **F13**, and its twin **F2** (the expiry scheduler abandoning restoration on a lock timeout).
One flaw in two places: **stock restoration is best-effort everywhere.**

## Questions I should be able to answer

- Why does `deductStock` need its own transaction rather than joining an outer one?
- What is Spring's default propagation, and why does it break the lock here?
- Describe how the Redis lock and the InnoDB row lock desynchronise under an outer transaction.
- What is given up by committing each deduction early, and why is it unavoidable?
- What is a saga, and what plays the role of the undo log here?
- Three ways compensation differs from rollback.
- Why is `restoreStock` deliberately unconditional?
- Why doesn't compensation have to undo the order row?
- In Trace B, why is the cart still full but inventory unchanged?
- In Trace C, what is the lasting damage and what would actually detect it?
