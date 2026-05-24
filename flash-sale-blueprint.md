# High-Concurrency Shopping Platform — Project Blueprint

> **Handoff note:** This is a step-by-step learning + resume project. Do NOT generate the entire project at once. Follow the Build Order in Section 10. Explain concepts at each step and wait for the user to confirm before proceeding.

---

## 1. Project Identity
- **Name:** `flash-sale-system` (folder + Maven artifactId stay; Java package is `com.example.shop`)
- **Type:** Spring Boot MVC REST API + minimal Vanilla JS frontend
- **Goal:** Resume project demonstrating a production-grade e-commerce backend with high-concurrency inventory control
- **Learning style:** Step-by-step; each step must be runnable before moving on

---

## 2. Tech Stack (locked)
```
Java 19
Spring Boot 3.x, Maven
Spring Security + JWT (jjwt 0.12.x)
MySQL 8       — transactional source of truth (products, inventory, orders); run locally, not in docker-compose
MongoDB 7     — activity logs (spring-data-mongodb)
Redis 7       — read cache only (cache-aside, NOT stock authority)
Redisson 3.x  — distributed lock for concurrency control on writes
Kafka         — async order/payment event pipeline (spring-kafka)
Docker + docker-compose
Frontend: plain HTML + Vanilla JS
Testing: JMeter for stress test
```

---

## 3. Functional Requirements

**Roles:** `USER`, `ADMIN`

**Admin can:**
- Create / update products and categories (initial stock supplied at product creation)
- Adjust product inventory (restock, correction, write-off) with cache invalidation
- View all orders

**User can:**
- Register / login (JWT)
- Browse products (list, filter by category, view detail with live stock)
- Add / update / remove items in their cart
- Checkout cart → places order (the hot path, high-concurrency)
- View own orders
- Trigger mock payment

---

## 4. Data Model

### MySQL Tables
```
users
  id, username, password_hash, role [USER|ADMIN], created_at

categories
  id, name, description

products
  id, name, description, price, category_id (FK), image_url, status [ACTIVE|INACTIVE], created_at

product_inventory
  id, product_id (FK, unique), total_stock, available_stock
  total_stock      — total units ever stocked; only increases via admin restock
  available_stock  — units currently buyable; decreases on checkout, increases on rollback / restock
  (sold = total_stock - available_stock)

carts
  id, user_id (FK, unique — one cart per user), created_at, updated_at
  → created in the same transaction as the user during POST /api/auth/register,
     so every authenticated user always has a cart row (no lazy-init branching)

cart_items
  id, cart_id (FK), product_id (FK), quantity
  UNIQUE(cart_id, product_id)

orders
  id, user_id (FK), status [PENDING|PAID|CANCELLED], total_amount, created_at, paid_at

order_items
  id, order_id (FK), product_id (FK), quantity, price_at_purchase
```

> `product_inventory` is separate from `products` intentionally — isolates the high-write concurrency surface from the catalog read surface. The row is created in the same transaction as the `products` row (see `POST /api/products` in Section 5) so a product never exists without an inventory record.

> No `@Version` / optimistic lock column. Per-product Redisson lock serializes writers, and the `WHERE available_stock >= ?` conditional UPDATE is the in-DB safety net. Adding `@Version` on top would only produce spurious `OptimisticLockingFailureException`s under contention.

> `price_at_purchase` on `order_items` snapshots the price at checkout time so later price changes don't alter historical orders.

### Redis Keys (cache only — never authoritative)
```
product:{id}:stock       → cached available_stock integer (TTL: 60s)
product:{id}:detail      → cached product+inventory JSON, embeds stock (TTL: 60s — must match :stock)
lock:product:{id}        → Redisson distributed lock (shared by checkout, expiry restore, admin inventory PATCH)
refresh:{jti}            → userId; presence = token is valid, absence = revoked (TTL: refresh-token expiry, 1d)
```

### MongoDB Collections
```
order_activity_log   (orderId, userId, event, timestamp, metadata)
                     written from OrderEventConsumer (after-commit Kafka events) and OrderExpiryScheduler

user_action_log      (userId, action, timestamp, ip)
                     written by UserActionLogFilter — runs after JwtFilter, writes async (@Async or
                     a queued executor) so request latency is unaffected
```

### Kafka Topics
```
order-created       → triggers downstream processing (logging, notifications)
payment-completed   → async status update PENDING → PAID + MongoDB log
```

---

## 5. Core API Endpoints
```
Auth
  POST /api/auth/register                       → creates user + cart in one tx
                                                  returns { accessToken (15m), refreshToken (1d) }
  POST /api/auth/login                          → returns { accessToken (15m), refreshToken (1d) }
                                                  stores refresh:{jti} → userId in Redis (TTL = 1d)
  POST /api/auth/refresh                        → body: { refreshToken }
                                                  verifies signature, looks up refresh:{jti} in Redis;
                                                  if missing → 401 (revoked or expired)
                                                  ROTATES: deletes old refresh:{jti}, issues new pair,
                                                  stores new refresh:{jti'} → userId
                                                  returns { accessToken, refreshToken }
  POST /api/auth/logout                         → body: { refreshToken }; deletes refresh:{jti} from Redis
                                                  (the 15m access token expires on its own)

Categories
  GET    /api/categories                        → list all
  POST   /api/categories            [ADMIN]     → create (body: { name, description })
  PUT    /api/categories/{id}       [ADMIN]     → update (body: { name, description })

Products
  GET    /api/products                          → list (filter: ?category=, ?search=; paginated: ?page=&size=)
  GET    /api/products/{id}                     → detail + live stock
  POST   /api/products              [ADMIN]     → create (body includes initialStock;
                                                  inserts products + product_inventory in one tx)
  PUT    /api/products/{id}         [ADMIN]     → update catalog fields (name, price, description, image_url, status [ACTIVE|INACTIVE], etc.)
                                                  status=INACTIVE acts as soft-delete (product hidden from listings, kept for order history)
  PATCH  /api/products/{id}/inventory [ADMIN]   → adjust stock by delta
                                                  body: { delta: +N or -N, reason: "restock"|"correction"|"damaged" }
                                                  acquires lock:product:{id} (same lock as checkout) for the duration of the write,
                                                  then cache-aside delayed double deletion on product:{id}:stock and product:{id}:detail

Cart
  GET    /api/cart                              → get my cart (items + quantities + per-item availability flag)
  POST   /api/cart/items                        → add item (or increment quantity); soft stock check (see below)
  PUT    /api/cart/items/{itemId}               → update quantity; soft stock check (see below)
  DELETE /api/cart/items/{itemId}               → remove item

  Soft stock check: if requested quantity > current available_stock, the request
    still succeeds (item is saved at the requested quantity) but the response
    includes a warning:
      { "cartItem": {...}, "warning": { "type": "STOCK_INSUFFICIENT",
                                         "available": 3, "requested": 5 } }
    The frontend renders the warning so the user can adjust. The hard
    enforcement is the conditional UPDATE in checkout (Section 7a step 6c).

Orders
  POST   /api/orders/checkout                   → checkout cart (the hot path)
  GET    /api/orders/me                         → my orders (paginated: ?page=&size=)
  GET    /api/orders/{id}                       → one order detail (owner-only; ADMIN can read any)
  POST   /api/orders/{id}/pay                   → mock payment trigger
  GET    /api/orders                [ADMIN]     → all orders (paginated: ?page=&size=)
```

---

## 6. Cache Strategy — Cache-Aside + Delayed Double Deletion

### Read path (product detail / stock level)
```
1. Check Redis key product:{id}:stock
2. Cache HIT  → return cached value
3. Cache MISS → read MySQL product_inventory.available_stock
             → populate Redis (with TTL)
             → return value
```

### Write path (admin inventory adjustment, or any service-layer stock write)
```
1. Acquire Redisson lock: lock:product:{id}                       ← serializes with checkout
                                                                   and other admin adjusts
2. Delete Redis keys product:{id}:stock AND product:{id}:detail   ← first deletion
   (detail also embeds the stock number, so it must be invalidated too)
3. UPDATE product_inventory SET ... WHERE product_id = ?  (MySQL is written)
4. Release Redisson lock
5. Schedule async second deletion of the same two keys after short delay (~500ms)
   (kills any stale entry a concurrent read may have cached between steps 2 and 3)
```

> Redis is **never** decremented during checkout. It is only invalidated. MySQL owns the number.
> Catalog-only updates (price, name, description) only need to invalidate `product:{id}:detail`, not `:stock`, and do not need the lock.
> The lock is shared with checkout, so admin adjusts and user checkouts on the same product serialize cleanly — admins cannot interleave with each other or with in-flight checkouts.

---

## 7. Critical Concurrency Flows — Checkout & Order Expiry

### 7a. Checkout (Hot Path)

Checkout processes cart items **sequentially**. If any item fails mid-way, all previously deducted stock is rolled back before returning an error.

> This is the **final** design. Build step 7 ships only the bare MySQL-conditional-UPDATE version (no lock, no cache invalidation) to demonstrate the race under JMeter; build step 8 adds steps 6a (lock), 6b (first deletion), and the delayed second deletions to reach the design described here.

```
POST /api/orders/checkout

1.  Validate JWT → resolve userId
2.  Load cart items for userId (must be non-empty).
    In the same read, snapshot { product_id, quantity, price, status }
    for every product in the cart. This snapshot is the single source of
    truth for prices and ACTIVE-ness throughout the rest of the checkout —
    no further re-reads from products.* during the loop.
3.  Reject the request if any snapshotted product.status != ACTIVE
    (return 400 "Product X is unavailable" — no deductions yet, no rollback needed).
4.  Initialize rollback list = [] (tracks successfully deducted products for step 8).

5.  Wrap steps 6 and 7 in a single try/catch — any exception thrown after
    the first successful deduction triggers the compensation in step 8.

6.  FOR EACH cart item (product_id, quantity):

    a. Acquire Redisson lock: lock:product:{id}
       → timeout: 5s wait, 10s lease

    b. Delete Redis keys product:{id}:stock and product:{id}:detail   ← first deletion (cache-aside write)

    c. MySQL safety check + deduction (atomic):
       UPDATE product_inventory
         SET available_stock = available_stock - ?
         WHERE product_id = ? AND available_stock >= ?
       → rowsAffected = 1 → success:
           - Add (product_id, quantity) to rollback list
           - Release Redisson lock
           - Schedule async delayed second deletion of product:{id}:stock
             and product:{id}:detail (~500ms)
           - Continue to next cart item
       → rowsAffected = 0 → SOLD_OUT for this item:
           - Release the lock before throwing, so we don't hold it during the unwind to step 8
           - throw SoldOutException(productId)
             (the catch block in step 8 handles compensation uniformly)

7.  All items deducted successfully (inside a single `@Transactional` block):
    a. Create Order row (status = PENDING, total_amount = sum of snapshot.price * qty)
    b. Create OrderItem rows with price_at_purchase = snapshot.price (from step 2)
    c. Clear user's cart (delete cart_items for this cart)
    d. Publish a Spring `OrderCreatedDomainEvent` via `ApplicationEventPublisher`
       (NOT KafkaTemplate directly — see "Event publishing semantics" below)
    Any exception inside this @Transactional block (DB connection drop,
    constraint violation, etc.) rolls back the order writes AND propagates
    to step 8 so the deductions from step 6 are compensated too.

8.  COMPENSATION (catch block — runs on ANY exception from step 6 or step 7):
    FOR EACH item in rollback list:
      a. Delete Redis keys product:{id}:stock and product:{id}:detail   ← first deletion
      b. UPDATE product_inventory
           SET available_stock = available_stock + ?
           WHERE product_id = ?
         (no lock needed — we are adding back, not racing to subtract)
      c. Schedule async delayed second deletion of the same two keys (~500ms)
      (same delete → update → delayed-delete order as Section 6 write path)
    Re-throw the original exception so the controller maps it to the
    appropriate HTTP error (409 for SoldOutException, 500 for unexpected, etc.).

9.  Return order summary to user.
```

> The order / order_items / cart-clear writes in step 7 are wrapped in a single `@Transactional` block so they commit atomically. Step 7d publishes an in-process Spring domain event; the actual Kafka send happens in the `@TransactionalEventListener(AFTER_COMMIT)` bridge, so it only fires **after** the tx commits (see "Event publishing semantics" below).

> Step 6 deductions are **outside** the step-7 transaction (each item is its own short MySQL UPDATE under its own per-product lock). That's why compensation is a manual concept in step 8 rather than a `@Transactional` rollback — the deductions have already been committed when step 7 begins.

> Locking is **per product**, not per order. This allows concurrent checkouts of different products to proceed in parallel — only checkouts touching the same product serialize against each other.

> **Known residual gap:** if compensation itself fails (MySQL is unreachable when step 8 runs), stock can leak. A production system would persist the rollback list to a durable compensation queue and retry. Out of scope for this build — README should call it out alongside the transactional outbox pattern.

### 7b. Order Expiry — Reclaim Stock from Abandoned Orders

Checkout deducts stock and creates the order with `status = PENDING`. If the user never pays, that stock would be stuck forever. A scheduled background job cancels expired orders and returns their stock to `available_stock`.

**Config (in `application.yml`):**
```
app:
  order:
    pending-timeout-minutes: 15        # how long a PENDING order is valid
    expiry-job-interval-seconds: 60    # how often the scanner runs
    expiry-job-batch-size: 100         # max orders processed per run
```

**Job flow (`@Scheduled`, runs every `expiry-job-interval-seconds`):**

```
1. SELECT id FROM orders
     WHERE status = 'PENDING'
       AND created_at < NOW() - INTERVAL ? MINUTE
     LIMIT N                                          ← batch cap

2. FOR EACH expired order:

   a. Atomically claim it:
      UPDATE orders
        SET status = 'CANCELLED'
        WHERE id = ? AND status = 'PENDING'
      → rowsAffected = 0 → the user paid (or another worker cancelled it)
                           in the same instant; skip this order entirely
      → rowsAffected = 1 → we own the cancellation; proceed to restore stock

   b. Load order_items for this order

   c. FOR EACH order_item (product_id, quantity):
        - Acquire Redisson lock: lock:product:{id}    ← same lock as checkout
        - Delete Redis keys product:{id}:stock and product:{id}:detail
        - UPDATE product_inventory
            SET available_stock = available_stock + ?
            WHERE product_id = ?
        - Release lock
        - Schedule async delayed second deletion of the two cache keys (~500ms)

   d. Write an order_activity_log entry to MongoDB (MongoDB is wired in at Step 10, so this lands as part of the scheduler in Step 11):
      { orderId, userId, event: "EXPIRED_CANCELLED", timestamp }
```

> The conditional `UPDATE orders ... WHERE status = 'PENDING'` is the **race guard**. It guarantees that exactly one of {`/pay` succeeds, expiry job cancels} wins — never both.

> `POST /api/orders/{id}/pay` must use the symmetric guard:
> `UPDATE orders SET status = 'PAID', paid_at = NOW() WHERE id = ? AND status = 'PENDING'`.
> If `rowsAffected = 0` the order was already cancelled by expiry — return 409 to the user.

> Stock restoration uses the **same Redisson lock as checkout** so it cannot race with a concurrent checkout / admin adjust on the same product.

### 7c. Event Publishing Semantics — Publish After Commit

Both `order-created` (checkout) and `payment-completed` (`/pay`) Kafka publishes must happen **after the DB transaction commits**, never inside it.

**Why:** if a service publishes to Kafka inside a transaction and the transaction then rolls back, a consumer will see an event for an order that doesn't exist (or for a state transition that never happened). Conversely, the DB write is the source of truth — if it's committed, downstream services need to find out, even if the publish would otherwise have been skipped because of an exception path.

**Pattern (Spring-idiomatic):**

```
1. Service layer publishes an in-process Spring event:
     applicationEventPublisher.publishEvent(new OrderCreatedDomainEvent(orderId, userId, ...))
   inside the @Transactional method, right after the DB writes.

2. A separate listener handles it after commit:

     @Component
     class OrderEventKafkaBridge {
       @TransactionalEventListener(phase = AFTER_COMMIT)
       public void onOrderCreated(OrderCreatedDomainEvent evt) {
         kafkaTemplate.send("order-created", evt.toKafkaPayload());
       }
     }

3. If the @Transactional method throws and rolls back, the listener is never called — no Kafka publish happens.
```

The same pattern applies to `POST /api/orders/{id}/pay`: the conditional `UPDATE orders ... WHERE status = 'PENDING'` runs inside the tx, a `PaymentCompletedDomainEvent` is published in-process, and the AFTER_COMMIT listener forwards it to the `payment-completed` Kafka topic.

> **Known residual gap:** if the JVM crashes between transaction commit and the Kafka send, the event is lost. The fully-robust fix is the **transactional outbox pattern** (write the event to an `outbox` table in the same tx, separate poller publishes to Kafka and marks rows sent). Out of scope for this build — but the README should call it out as the natural next step.

---

## 8. Project Structure
```
flash-sale-system/
├── docker-compose.yml
├── pom.xml
├── README.md
├── .gitignore
├── src/main/java/com/example/shop/
│   ├── ShopApplication.java
│   ├── config/
│   │   ├── RedissonConfig.java
│   │   ├── KafkaConfig.java
│   │   └── MongoConfig.java
│   ├── controller/
│   │   ├── AuthController.java
│   │   ├── CategoryController.java
│   │   ├── ProductController.java
│   │   ├── CartController.java
│   │   └── OrderController.java
│   ├── service/
│   │   ├── AuthService.java
│   │   ├── RefreshTokenService.java ← issue / rotate / revoke refresh:{jti} in Redis
│   │   ├── ProductService.java      ← cache-aside + delayed double deletion lives here
│   │   ├── CartService.java
│   │   └── OrderService.java        ← checkout concurrency logic lives here
│   ├── scheduler/
│   │   └── OrderExpiryScheduler.java ← @Scheduled job that cancels PENDING orders + restores stock
│   ├── repository/
│   │   ├── jpa/
│   │   │   ├── UserRepository.java
│   │   │   ├── CategoryRepository.java
│   │   │   ├── ProductRepository.java
│   │   │   ├── ProductInventoryRepository.java
│   │   │   ├── CartRepository.java
│   │   │   ├── CartItemRepository.java
│   │   │   ├── OrderRepository.java
│   │   │   └── OrderItemRepository.java
│   │   └── mongo/
│   │       ├── OrderActivityLogRepository.java
│   │       └── UserActionLogRepository.java
│   ├── entity/                      ← MySQL JPA entities
│   │   ├── User.java
│   │   ├── Category.java
│   │   ├── Product.java
│   │   ├── ProductInventory.java
│   │   ├── Cart.java
│   │   ├── CartItem.java
│   │   ├── Order.java
│   │   └── OrderItem.java
│   ├── document/                    ← MongoDB documents
│   │   ├── OrderActivityLog.java
│   │   └── UserActionLog.java
│   ├── dto/
│   │   ├── auth/
│   │   ├── product/
│   │   ├── cart/
│   │   └── order/
│   ├── enums/
│   │   ├── Role.java
│   │   ├── OrderStatus.java
│   │   └── ProductStatus.java
│   ├── security/
│   │   ├── JwtFilter.java
│   │   ├── JwtUtil.java
│   │   ├── UserDetailsServiceImpl.java
│   │   ├── UserActionLogFilter.java ← runs after JwtFilter; async-writes user_action_log on each authenticated request
│   │   └── config/
│   │       └── SecurityConfig.java
│   ├── kafka/
│   │   ├── producer/
│   │   │   └── OrderEventKafkaBridge.java     ← @TransactionalEventListener(AFTER_COMMIT)
│   │   │                                         that uses KafkaTemplate to forward
│   │   │                                         in-process domain events to Kafka topics
│   │   ├── consumer/
│   │   │   └── OrderEventConsumer.java        ← listens on order-created & payment-completed,
│   │   │                                         writes order_activity_log to MongoDB
│   │   └── event/
│   │       ├── OrderCreatedEvent.java         ← Kafka wire payload (topic: order-created)
│   │       ├── PaymentCompletedEvent.java     ← Kafka wire payload (topic: payment-completed)
│   │       ├── OrderCreatedDomainEvent.java   ← in-process Spring event (published in tx)
│   │       └── PaymentCompletedDomainEvent.java ← in-process Spring event (published in tx)
│   └── exception/
│       ├── GlobalExceptionHandler.java
│       ├── SoldOutException.java
│       └── ResourceNotFoundException.java
├── src/main/resources/
│   ├── application.yml
│   └── static/                      ← frontend
│       ├── index.html               (product list)
│       ├── login.html
│       ├── register.html
│       ├── cart.html
│       ├── orders.html
│       ├── admin/
│       │   ├── products.html        (create / update products, includes initialStock)
│       │   ├── inventory.html       (PATCH inventory: delta + reason)
│       │   └── orders.html          (view all orders)
│       └── js/
│           ├── api.js               (shared fetch wrapper + JWT header)
│           ├── auth.js              (login / register / token storage)
│           ├── products.js
│           ├── cart.js
│           ├── orders.js
│           └── admin/
│               ├── products.js
│               ├── inventory.js
│               └── orders.js
└── jmeter/
    └── checkout-stress-test.jmx
```

---

## 9. Docker Compose Services
- `mongo:7` (port 27017)
- `redis:7` (port 6379)
- `zookeeper` + `kafka` (ports 2181, 9092)
- *(optional)* `kafka-ui`, `redis-commander`, `mongo-express` for debugging

> **MySQL is run locally (not containerized)** — Spring Boot connects to `localhost:3306` directly. This matches the current dev setup and avoids volume/perf overhead during stress testing.

---

## 10. Build Order — one step at a time, each must run before proceeding

| Step | What | Key concept taught |
|------|------|--------------------|
| 1 | `docker-compose.yml` — mongo, redis, zookeeper, kafka (MySQL runs locally) | Container orchestration |
| 2 | `pom.xml` + Spring Boot skeleton + `application.yml` | Project wiring |
| 3 | MySQL entities + repositories (all 8 tables) | JPA relationships, FK strategy |
| 4 | Spring Security + dual-token JWT (access 15m + refresh 1d, refresh:{jti} stored in Redis, rotation on /refresh, revocation on /logout) — register / login / refresh / logout end-to-end | Stateless access tokens, stateful refresh tokens, token rotation |
| 5 | Category + Product CRUD + inventory adjust admin endpoint | REST design, admin role guard |
| 6 | Cart API (add / update / remove items) | Session-less cart via DB |
| 7 | Checkout — sequential deduction with rollback *(no lock yet)* | See the race condition with JMeter |
| 8 | Add Redisson lock to checkout + cache-aside + delayed double deletion | Concurrency fix, cache consistency |
| 9 | Kafka — async order-created + payment-completed pipeline | Event-driven decoupling |
| 10 | MongoDB — `order_activity_log` on checkout/payment events + `user_action_log` via a request filter | Polyglot persistence |
| 11 | `OrderExpiryScheduler` — `@Scheduled` job that cancels PENDING orders past timeout and restores stock under the per-product Redisson lock; conditional UPDATE on `/pay` to guard against the race | Background jobs, race-free state transitions |
| 12 | Vanilla JS frontend — public (login, register, product list, cart, checkout, my orders) + admin (products, inventory, all orders) | Minimal UI to demo the system; role-gated nav |
| 13 | JMeter stress test + README with benchmark results | Prove the concurrency story |

---

## 11. Resume Bullets (target after Step 13)
- Built a high-concurrency e-commerce backend handling **X RPS** with zero overselling, using Redisson distributed locks and MySQL as the authoritative stock source
- Implemented cache-aside with delayed double deletion to maintain Redis/MySQL consistency under concurrent reads and writes
- Designed sequential multi-item checkout with per-product locking and automatic stock rollback on partial failure
- Decoupled order placement from payment processing via Kafka, reducing P99 latency from **Xms → Yms**
- Polyglot persistence: MySQL for transactional data, MongoDB for activity logs, Redis for read cache
- Containerized with Docker Compose; stress-tested with JMeter with documented benchmark methodology

*(Fill X/Y from Step 13 JMeter results.)*

---

## 12. Confirmed Decisions
- Java: **19**, Spring Boot 3.x, Maven
- Package: `com.example.shop`
- Flash sale concept: **dropped** — this is a general shopping platform
- Cache strategy: **cache-aside** (Redis = read cache, MySQL = source of truth)
- Cache invalidation: **delayed double deletion** on both `product:{id}:stock` and `product:{id}:detail`
- Concurrency control: **Redisson distributed lock per product** (not per order); the **same `lock:product:{id}`** is acquired by checkout, order-expiry stock restore, and admin inventory PATCH — there is exactly one writer per product at any moment
- Oversell prevention: **MySQL `WHERE available_stock >= ?`** (rowsAffected check), lock ensures serialization
- Optimistic lock (`@Version`) on `product_inventory`: **not used** — Redisson lock + conditional UPDATE is the chosen strategy
- Stock model: **two columns** — `total_stock` (only restocks raise it) and `available_stock` (checkout/rollback move it); `sold = total - available`
- Inventory lifecycle: **`POST /api/products` creates the inventory row with `initialStock` in the same tx**; later changes go through `PATCH /api/products/{id}/inventory` (delta + reason)
- Price snapshot: **read once at start of checkout** (with cart load), reused for both deduction loop and `price_at_purchase`
- Multi-item checkout failure: **sequential processing, roll back all previously deducted items** (lock released before rollback runs)
- PENDING order lifecycle: **`@Scheduled` `OrderExpiryScheduler` cancels PENDING orders older than `app.order.pending-timeout-minutes` (default 15) and restores stock under the per-product Redisson lock**; `/pay` uses a conditional UPDATE so exactly one of {pay, expire} wins
- Payment: **mocked** via Kafka async flow
- Kafka publish atomicity: **publish after DB commit** via in-process Spring domain events + `@TransactionalEventListener(AFTER_COMMIT)`; transactional outbox pattern acknowledged as the production-grade next step but out of scope for this build
- JWT: **dual-token** — short-lived access (15m, stateless) + long-lived refresh (1d, stateful in Redis as `refresh:{jti}`); `/refresh` rotates the pair, `/logout` deletes the Redis key
- JWT secret: **kept in `application.yml`** as a literal for this dev/learning project (would be env-var-loaded in production)
- Cart lifecycle: **created at registration** in the same tx as the user — every authenticated user always has a cart row
- Cart-vs-stock check: **soft warning at add/update** (request accepted, response carries `STOCK_INSUFFICIENT` warning); **hard enforcement at checkout** via the conditional UPDATE
- Frontend: **Vanilla JS** (plain HTML + JS, no framework); pages include public (login, register, products, cart, my orders) and admin (products, inventory, all orders)
- Teaching mode: **step-by-step**, one step at a time
