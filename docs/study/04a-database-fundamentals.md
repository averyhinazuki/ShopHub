# 04a — Database fundamentals

Inserted before `04-jpa-and-transactions.md`. Assumes you can write `SELECT` / `INSERT` /
`UPDATE` / `JOIN` and nothing beyond that. Everything else gets built here.

Everything in Units 5–9 (caching, locks, inventory, order races) sits on this. Shaky here means
shaky everywhere, so we go slowly.

---

# Concept 1 — How the database finds rows ✅

## Full table scans

```sql
SELECT * FROM orders WHERE user_id = 7;
```

With no help, MySQL has exactly one option: read row 1, check `user_id`. Read row 2, check. Read
row 3, check. Every row in the table. That's a **full table scan**.

At 200 rows it's instant and nobody notices. At 500,000 rows it reads half a million rows to
return your six — **on every page load.**

## Indexes

An **index** is a sorted lookup structure the database maintains on the side. Same idea as the
index at the back of a textbook: rather than reading every page to find "photosynthesis," you
look it up and it says *page 412*.

```sql
KEY `idx_orders_user` (`user_id`)
```

Your schema is full of these — `idx_products_category`, `idx_cart_items_product`,
`idx_order_items_order`, `idx_order_items_product`. Each exists so lookups on that column don't
scan.

**The cost:** an index is extra storage, and every `INSERT`/`UPDATE`/`DELETE` must also update the
index. So you index the columns you *search by*, not every column.

---

# Concept 2 — Constraints: rules the database enforces ✅

A constraint is a rule you ask the database to enforce, so bad data is **rejected at write time**
rather than discovered later.

| Constraint | Means | Example in your schema |
|---|---|---|
| `PRIMARY KEY` | unique + not null, the row's identity | every table: `PRIMARY KEY (id)` |
| `AUTO_INCREMENT` | database assigns the next number | every `id` |
| `NOT NULL` | must have a value | `products.name`, `orders.total_amount` |
| `UNIQUE KEY` | no two rows may share this value | `uk_users_username`, `uk_carts_user` |
| `FOREIGN KEY` | this value **must exist** in another table | `fk_cart_items_cart` |

The foreign key is the interesting one:

```sql
CONSTRAINT `fk_cart_items_cart` FOREIGN KEY (`cart_id`) REFERENCES `carts` (`id`)
```

*"`cart_items.cart_id` must be the id of a real row in `carts`."* Insert a cart item pointing at
cart 99999 when no such cart exists and **MySQL rejects the insert**. Without the constraint the
column is just a number and nothing checks it.

Also note `uk_cart_items_cart_product (cart_id, product_id)` — a **composite** unique key. It says
one cart cannot contain the same product twice as two rows; adding the same product again must
update the existing row's quantity.

## Where ShopHub is missing both

Every relationship column has an index and an FK constraint — except one:

| Table | Column | Index? | FK? |
|---|---|---|---|
| `products` | `category_id` | ✅ | ✅ |
| `product_inventory` | `product_id` | ✅ | ✅ |
| `cart_items` | `cart_id`, `product_id` | ✅ | ✅ |
| `order_items` | `order_id`, `product_id` | ✅ | ✅ |
| **`orders`** | **`user_id`** | ❌ | ❌ |

Consequences: `getMyOrders` full-scans every order ever placed; and an order can reference a user
that doesn't exist. (→ **F10**)

---

# Concept 3 — Transactions ✅

## The problem

Creating an order is several statements — insert the order, insert each item, clear the cart.
Crash halfway and you get order 42 claiming `258.00`, containing one item worth `129.00`, and a
cart that's still full. Permanently wrong, with nothing recording that it went wrong.

## The mechanism

```sql
START TRANSACTION;      -- from here, nothing is permanent
INSERT INTO orders ...;
INSERT INTO order_items ...;
DELETE FROM cart_items ...;
COMMIT;                 -- NOW all of it becomes permanent, together
```

- **`COMMIT`** — every change becomes permanent, simultaneously
- **`ROLLBACK`** — every change is discarded, as if nothing ran
- Connection dies before either → automatic rollback

So the mid-crash now leaves **nothing**: no order, no items, cart intact. The user retries and it
works.

## ACID — the two that matter constantly

**A — Atomicity.** All or nothing.

**I — Isolation.** While a transaction is open, **nobody else can see its changes.** Another
connection running `SELECT * FROM orders WHERE id = 42` gets **zero rows** even though the
`INSERT` already ran — until `COMMIT`.

> The visibility boundary is **`COMMIT`**, not execution. Fifty statements can be entirely
> invisible; one statement plus a commit is instantly visible. Nothing is visible before commit,
> and everything becomes visible at commit, together.

(*C — Consistency*: constraints hold before and after. *D — Durability*: committed survives a
power cut. Both mostly the database's problem.)

## Where it is in ShopHub

`OrderService.persistOrder:226-261` is exactly that transaction — `@Transactional` opens it on
entry, commits on normal return, **rolls back if the method throws**. You never write
`START TRANSACTION` by hand. *How* the annotation does this is Unit 4 and it's stranger than it
looks.

## Why AFTER_COMMIT exists — isolation is the reason

Sending `order-created` from *inside* `persistOrder`:

1. `INSERT INTO orders` runs — order 42 exists **only inside this transaction**
2. The Kafka message is sent — now real and visible to everyone
3. A consumer picks it up milliseconds later and runs `SELECT ... WHERE id = 42`
4. **Zero rows.** To that consumer, order 42 does not exist

You announced something the world cannot yet see. The `AFTER_COMMIT` bridge fires only after the
commit, so by the time anyone hears about order 42, it's there. (→ `03`)

## Gotcha

**Auto-increment advances even on rollback.** A rolled-back insert still consumes its id, so the
next successful order is 43. **Gaps in id sequences are normal** and are not evidence of lost data.

---

# Concept 4 — Concurrency: two writers, one row ✅

## The lost update problem

One item left. Two buyers. Written as read → check → write:

```
Alice: SELECT available_stock → 1
Bob:   SELECT available_stock → 1          ← both now hold a stale 1
Alice: checks 1 >= 1 ✓, UPDATE
Bob:   checks 1 >= 1 ✓, UPDATE
```

**Two orders, one item.** No single line is buggy — the failure is entirely in the
**interleaving**, which is why it only appears under load and is hell to reproduce. The general
unsafe shape is **read-modify-write**.

**The damage happens at the READS, not the writes.** Both transactions committed to a decision
based on a value that was never exclusively theirs; the writes merely executed an
already-wrong decision. This is why locking the write cannot fix it — Bob's stale `1` is already
sitting in a Java variable, and no lock reaches back to correct a decision already made.

## Row locks are real but insufficient

InnoDB locks a row on update; a second transaction's `UPDATE` on that row **waits** until the
first commits. Writes are safely serialized. That still doesn't save the code above:

```sql
SET available_stock = 0                    -- value computed in Java → both write 0 → ends at 0
SET available_stock = available_stock - 1  -- arithmetic in the DB → applied twice → ends at -1
```

With the second form the lock works *perfectly*, both updates serialize, each subtracts one, and
you land at **-1**. Negative stock, with correct locking. The missing piece was never the lock —
it was the `WHERE` guard.

## The fix: one conditional statement

```sql
UPDATE product_inventory
   SET available_stock = available_stock - 1
 WHERE product_id = 3
   AND available_stock >= 1;
```

Atomically, in one statement: find and **lock** the row → evaluate the condition against its
**current committed value** → subtract if true.

Replaying the race: Alice locks, sees `1 >= 1`, writes `0`, commits. Bob was **blocked on the
lock the entire time** — not failing, not retrying, just waiting. He wakes, acquires the lock,
**re-reads the row (now `0`)**, evaluates `0 >= 1` → false → changes nothing → returns `0`.

> A conditional `UPDATE` evaluates its `WHERE` at the moment it **holds the lock**, not when you
> submitted it. Bob's statement was written when stock was 1 and evaluated when it was 0. The
> check happens as late as physically possible, with the row held still.

> **`rows == 0` IS the sold-out signal** — not an error to check afterwards, but the database
> reporting that the condition failed at the exact instant it mattered.

## Where it is in ShopHub

`ProductInventoryRepository:19-21` — `deductStock`, returning **`int`** (the row count).
`OrderService:144-148`:

```java
int rows = inventoryRepository.deductStock(item.productId(), item.qty());
if (rows == 0) { lock.unlock(); throw new SoldOutException(item.productId()); }
```

That is the entire oversell defense. **There is no `if (stock >= qty)` anywhere in the service
layer** — a check in Java is a check on a value that may already be stale.

Same pattern on orders, `OrderRepository:34-36`:

```java
@Query("UPDATE Order o SET o.status = 'PAID', o.paidAt = :now WHERE o.id = :id AND o.status = 'PENDING'")
int payIfPending(Long id, LocalDateTime now);
```

*"Change it, but only if it's still in the state I expect."* `rows == 1` = you won,
`rows == 0` = someone beat you. That's how `/pay` and the expiry scheduler race safely (→ `09`).

## Gotcha — the dangerous "improvement"

```java
int stock = inventoryRepository.findStockByProductId(productId);   // SELECT
if (stock >= qty) {
    inventoryRepository.deductStock(productId, qty);               // UPDATE, result discarded
}
```

Two problems. The obvious one: a gap between the `SELECT` and the `UPDATE` that another
transaction can enter.

The nastier one is human: `deductStock` **still has its guard**, so this doesn't actually
oversell — but the `if` *looks* like the check, so whoever writes it stops checking the return
value. The `UPDATE` silently returns `0`, the real protection fires, and **nobody looks**. No
exception, no `SoldOutException`, no `FAILED` status. An order is created and the stock was never
deducted.

> **The row count is not a diagnostic. It is the business logic.** Discarding it discards the check.

## Questions I should be able to answer

- What's a full table scan, and when does it stop being acceptable?
- What does a `FOREIGN KEY` constraint actually prevent?
- What are `COMMIT` and `ROLLBACK`, and what happens if the connection dies before either?
- What is the visibility boundary for other connections — and what does another connection see
  mid-transaction?
- Why does the AFTER_COMMIT bridge exist, in terms of isolation?
- In a lost update, at which moment does the data become wrong — the reads or the writes? Why does
  that determine where the fix goes?
- Why don't row locks alone prevent overselling? What does `available_stock - 1` twice produce?
- Why does a conditional `UPDATE` fix it? When is its `WHERE` evaluated?
- Why is `rows == 0` the sold-out signal rather than an error code?
- What is wrong with checking `stock >= qty` in Java first?
