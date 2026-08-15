# 04 — JPA, the persistence context, and Spring proxies

Unit 4. Prerequisite: `04a-database-fundamentals.md` (indexes, constraints, transactions,
concurrency). Read that first — this file assumes `COMMIT` is the visibility boundary and that a
conditional `UPDATE` is how you avoid lost updates.

---

# Concept 1 — An ORM is a machine that writes your SQL ✅

## The one substitution at the heart of it

A foreign key column holds **a number**; an ORM lets your code hold **the object**:

```sql
`product_id` bigint NOT NULL          -- the column
```
```java
private Product product;              // the field
```

Same thing in the database, different thing in memory. **Everything else in Hibernate exists to
manage that substitution** — when to load the object behind the number, when to write the number
back, and how to avoid loading the same row twice.

## Your schema next to your entity

```sql
CREATE TABLE `product_inventory` (
  `id` bigint NOT NULL AUTO_INCREMENT,       -- ① @Id @GeneratedValue(IDENTITY)
  `available_stock` int NOT NULL,            -- ② @Column(nullable=false)
  `total_stock` int NOT NULL,                -- ②
  `product_id` bigint NOT NULL,              -- ③ @JoinColumn(name="product_id", nullable=false)
  PRIMARY KEY (`id`),                        -- ①
  UNIQUE KEY `uk_...` (`product_id`),        -- ④ @JoinColumn(unique=true) / @OneToOne
  CONSTRAINT `fk_...` FOREIGN KEY (`product_id`) REFERENCES `products`(`id`)   -- ③
) ENGINE=InnoDB;
```

No magic — it's the DDL you'd write by hand, declared on the class instead.

**Naming:** `availableStock` ↔ `available_stock`. Hibernate converts camelCase → snake_case by
default, which is why most `@Column` annotations here only exist to declare `nullable = false`.

## The layers

| Layer | What it is | In ShopHub |
|---|---|---|
| **Spring Data JPA** | Generates repository *implementations* from method names | `findByUserId`, `findByCartId` — you never wrote the bodies |
| **JPA** | The **specification** (`jakarta.persistence.*`) | `@Entity`, `@Column`, `@ManyToOne` |
| **Hibernate** | The **implementation** doing the work | the default; generates your SQL |
| **JDBC** | The raw driver API | `com.mysql.cj.jdbc.Driver` |

`findByCartId(id)` → Spring Data parses the name → Hibernate builds SQL → JDBC runs it →
Hibernate maps the `ResultSet` back to objects.

**JPQL** is SQL written against the object model — `ProductInventoryRepository:19-21` says
`ProductInventory` and `pi.product.id`, not `product_inventory` and `product_id`.

## Vocabulary

| SQL | Hibernate |
|---|---|
| table | **entity** |
| row | entity instance |
| column | field |
| primary key | `@Id` |
| foreign key column | `@JoinColumn` |
| `JOIN` to follow an FK | **navigate an association** — `orderItem.getProduct()` |
| connection + transaction | **persistence context** / `EntityManager` |

## Notes on ShopHub's mappings

- **`@Enumerated(EnumType.STRING)` everywhere** (`Order:22`, `Product:40`, `User:23`). Stores
  `"PENDING"` as text. `ORDINAL` would store the enum's *position*, so reordering constants would
  silently reinterpret every existing row.
- **Every association is `LAZY`** — `OrderItem:17,21`, `Product:34,46`, `ProductInventory:15`,
  `CartItem:17,21`. Uniform and deliberate; see Concept 3 for what it costs.
- **`Order` has no collection of items** — no `@OneToMany`. The link exists only on
  `OrderItem.order` (`:17-19`), so items are fetched via `orderItemRepository.findByOrderId(...)`.
- **`Order.userId` is a plain `Long`** (`:19-20`), not a `@ManyToOne User`. Root cause of both
  **F7** (username→id lookup per request) and **F10** (no index, no FK on `orders.user_id`).
- **Hibernate does not own the schema** — `application.yml:19` is `ddl-auto: validate`. Flyway
  owns it; Hibernate only verifies the mapping matches at startup.

---

# Concept 2 — The persistence context ✅

## The problem

Load product 7 twice in one transaction — do you get two Java objects for one row? If so, change
one and the other is stale, and Hibernate can't know which is the truth.

## The mechanism

Every transaction gets a **persistence context**: a `Map` of *(entity type, id)* → *object*,
living for that transaction. Also called the **first-level cache** or **identity map**. Three jobs:

**Job 1 — identity map.** One row, one object, per transaction.

```java
Product a = productRepository.findById(7L).get();   // SELECT
Product b = productRepository.findById(7L).get();   // NO SQL — same object.  a == b → true
```

**Job 2 — dirty checking.** On load, Hibernate keeps a **snapshot** of every field. At commit it
compares current vs snapshot and emits `UPDATE`s for what changed:

```java
@Transactional
public void rename(Long id, String newName) {
    Product p = productRepository.findById(id).get();
    p.setName(newName);
    // no save(). The UPDATE still happens at commit.
}
```

**A loaded entity is live.** You mutate it; the transaction writes the difference.

**Job 3 — write-behind.** SQL is buffered until **flush** (just before commit), so Hibernate can
batch and order statements to respect foreign keys.

## Entity states

| State | Meaning |
|---|---|
| **transient** | `new Order()` — never seen by Hibernate, no id, untracked |
| **managed** | in the persistence context — dirty-checked, changes will be written |
| **detached** | was managed, transaction ended — changes no longer tracked |

`persistOrder:232-237` walks the first transition: `new Order()` is transient; after
`orderRepository.save(order)` it's **managed**, which is why `order.getId()` at `:254` has a value.

## What `readOnly = true` actually does

**It turns off Job 2.** No snapshot is taken and no flush happens at commit, saving memory (no
duplicate of every loaded entity) and CPU (no field-by-field comparison pass). On a read touching
many rows that's real, and it was guaranteed-wasted work. It also marks the JDBC connection
read-only, which some setups use to route to a replica.

`loadCartSnapshot:201-219` is exactly this case — loads cart items purely to read prices and
validate status, modifies nothing.

⚠️ **It's a hint, not a guarantee.** It won't stop a `@Modifying` query or raw SQL from writing.

⚠️ **And it discards changes silently.** Mutate a managed entity under `readOnly = true` and there
is no exception, no warning — the snapshot was never taken, so nothing is compared and nothing is
written. The **in-memory object still shows the new value**, so if you return it, the caller sees
the change and believes it saved. Tests asserting on the returned object pass.

## What `clearAutomatically = true` actually does

A `@Modifying` bulk update goes **straight to the database as SQL**. It does **not** pass through
the persistence context, and Hibernate has no idea which rows it touched.

`OrderService.pay`:

```java
Order existing = orderRepository.findById(orderId)...;   // :274 order 42 MANAGED, cached PENDING
int rows = orderRepository.payIfPending(orderId, now);   // :281 DB now says PAID.
                                                          //      Cached object still says PENDING.
Order order = orderRepository.findById(orderId)...;      // :289 Job 1: "already have order 42"
                                                          //      → returns CACHED object, NO SQL.
                                                          //      Still PENDING, paidAt null.
```

Without `clearAutomatically`, `pay()` would return `PENDING` with no `paidAt` **immediately after
successfully paying**. The identity map, doing its job correctly, hands back a stale object.

`clearAutomatically = true` (`OrderRepository:34`) empties the context after the update, so `:289`
finds nothing cached and actually queries. Exactly what the comment at `OrderService:288` says.

**It must be requested manually** because Hibernate cannot know which entities a bulk update
invalidated — it would have to parse your JPQL and reason about which cached rows match the
`WHERE`, or throw away the whole cache after every bulk update. It does neither.

---

# Concept 3 — Proxies, and why `self` exists ✅

## The problem

`@Transactional` is an annotation — pure metadata. Annotations don't execute. Something must
issue `START TRANSACTION` before the method and `COMMIT` after.

## The mechanism

At startup Spring **does not put your object in the container.** It creates a **proxy**: same type
(a generated subclass), holding a reference to your real instance, running logic before and after
each call.

```java
class OrderService$$Proxy extends OrderService {
    private final OrderService real;
    @Override public OrderResponse pay(Long id) {
        tx.begin();
        try { OrderResponse r = real.pay(id); tx.commit(); return r; }
        catch (RuntimeException e) { tx.rollback(); throw e; }
    }
}
```

**`OrderController` autowires `OrderService` and receives the proxy**, not your instance.

```
OrderController → [proxy: begin] → real OrderService.pay() → [proxy: commit] → response
```

## The consequence: `this.` bypasses everything

Inside your real object, `this` **is the real object** — it doesn't know a proxy exists. So
`loadCartSnapshot(userId)` (implicitly `this.loadCartSnapshot(...)`) jumps straight from one real
method to another. The proxy is never involved and **`@Transactional` does nothing** — silently.
No error, no startup warning, no log.

## Why it's load-bearing here

`loadCartSnapshot:210-212` touches lazy associations. Without a transaction:

1. `cartItemRepository.findByCartId(...)` still works — Spring Data repository methods open their
   own small transaction internally.
2. That transaction ends on return. The `CartItem`s come back **detached**.
3. `ci.getProduct()` (`:211`) returns an uninitialized lazy placeholder — still fine.
4. `p.getStatus()` (`:212`) touches it, tries to fire the `SELECT`, finds no open session →
   **`LazyInitializationException`**.

**And the thread decides whether you see it.** In an HTTP request, Spring Boot's `open-in-view`
(on by default) keeps a session open for the whole request, so the lazy load would *accidentally*
succeed. But `processCheckout` runs on a **Kafka listener thread** (`CheckoutRequestedConsumer:69`)
— no request, no session, guaranteed throw.

> Same code passes through a web endpoint and fails on the async path. Same shape as **F8**: the
> async path is where things hide.

## The fix — `OrderService:64-69`

```java
@Lazy
@Autowired
private OrderService self;
```

Spring injects **the proxy** here, so `self.loadCartSnapshot(...)` (`:130`) and
`self.persistOrder(...)` (`:162`) go out through the wrapper and back in, and the transaction
applies.

**Why `@Lazy`:** building `OrderService` would require `OrderService` — a construction-time cycle.
`@Lazy` injects a stand-in resolved on first use, breaking it.

## This applies to every proxy-based annotation

`@Async`, `@Cacheable`, `@PreAuthorize` — a `this.` call to any of them silently does nothing.

Which is why `ProductCacheService` is a **separate bean** (its class comment at `:24` says so
explicitly). `OrderService:151` calls `cacheService.scheduleSecondDeletion(...)` — a *different*
object, so that bean's proxy applies and it really runs asynchronously.

Inline it as a private method and it runs **synchronously on the calling thread**:
`Thread.sleep(500)` (`ProductCacheService:88`) blocks the **Kafka listener thread** for 500ms *per
product* — a three-item cart adds **1.5 seconds** to every checkout. The cache is still deleted
twice, nothing is functionally wrong, no test fails. The only symptom is unexplained slowness.

## Questions I should be able to answer

- What single substitution is an ORM built around?
- Difference between JPA, Hibernate, and Spring Data JPA?
- Why `EnumType.STRING` rather than `ORDINAL`?
- What are the persistence context's three jobs?
- Why does a `setName()` with no `save()` still reach the database?
- What does `readOnly = true` switch off, and why is a discarded write especially dangerous?
- Why does `payIfPending` need `clearAutomatically`, and why can't Hibernate infer it?
- What is a Spring proxy, and what does a caller actually receive when it autowires a service?
- Why does `this.someTransactionalMethod()` silently do nothing?
- Why does removing `self.` break checkout — and why might a web-endpoint test still pass?
- What silently changes if an `@Async` method is inlined into its caller?
