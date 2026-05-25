# flash-sale-system — Progress Log

## Project
High-concurrency shopping platform · Spring Boot 3.x · Java 19 · Maven  
Package: `com.example.flashsale`  
Blueprint reference: `flash-sale-blueprint.md`

---

## ✅ Step 1 — Docker Infrastructure
**Status:** Complete  
**File:** `src/main/resources/docker-compose.yml`

Services configured and verified running:
- `flash-mongo` — MongoDB 7 (port 27017)
- `flash-redis` — Redis 7 (port 6379)
- `flash-zookeeper` — Confluent Zookeeper 7.6.0 (port 2181)
- `flash-kafka` — Confluent Kafka 7.6.0 (port 9092)

MySQL runs locally (not containerised) — Spring Boot connects to `localhost:3306`.

---

## ✅ Step 2 — Project Skeleton + Config
**Status:** Complete

### pom.xml
All dependencies locked:
- Spring Boot 3.5.x (web, data-jpa, data-mongodb, data-redis, security, validation, kafka)
- `redisson-spring-boot-starter` 3.27.2
- `jjwt-api / jjwt-impl / jjwt-jackson` 0.12.6
- Lombok, MySQL connector

### application.yml — changes made
- Split single `app.jwt.expiration-ms` into dual-token config:
  ```yaml
  app:
    jwt:
      secret: your-256-bit-secret-key-replace-this-in-production-please
      access-expiration-ms: 900000     # 15 minutes
      refresh-expiration-ms: 86400000  # 1 day
    order:
      pending-timeout-minutes: 15
      expiry-job-interval-seconds: 60
      expiry-job-batch-size: 100
  ```

---

## ✅ Step 3 — MySQL Entities + Repositories
**Status:** Complete  
All 8 tables created via `ddl-auto: update` on startup.

### Entities created / updated

| File | Action | Notes |
|------|--------|-------|
| `entity/User.java` | Existed | No changes needed |
| `entity/Order.java` | **Updated** | Removed `flashSaleId`, added `totalAmount (BigDecimal)` |
| `entity/Category.java` | **New** | `id, name (unique), description` |
| `entity/Product.java` | **New** | `id, name, description, price, category (FK), imageUrl, status, createdAt` |
| `entity/ProductInventory.java` | **New** | `id, product (OneToOne FK unique), totalStock, availableStock` |
| `entity/Cart.java` | **New** | `id, userId (unique), items (OneToMany), createdAt, updatedAt` |
| `entity/CartItem.java` | **New** | `id, cart (FK), product (FK), quantity` · UNIQUE(cart_id, product_id) |
| `entity/OrderItem.java` | **New** | `id, order (FK), product (FK), quantity, priceAtPurchase` |

### Enums created

| File | Action |
|------|--------|
| `enums/Role.java` | Existed — `USER, ADMIN` |
| `enums/OrderStatus.java` | Existed — `PENDING, PAID, CANCELLED` |
| `enums/ProductStatus.java` | **New** — `ACTIVE, INACTIVE` |

### JPA Repositories created / updated

| File | Action | Key methods |
|------|--------|-------------|
| `repository/jpa/UserRepository.java` | Existed | `findByUsername`, `existsByUsername` |
| `repository/jpa/CategoryRepository.java` | **New** | Standard JPA |
| `repository/jpa/ProductRepository.java` | **New** | `findByStatus`, `findByStatusAndCategoryId`, `findByStatusAndNameContainingIgnoreCase` |
| `repository/jpa/ProductInventoryRepository.java` | **New** | `deductStock` (conditional UPDATE), `restoreStock`, `adjustStock` |
| `repository/jpa/CartRepository.java` | **New** | `findByUserId` |
| `repository/jpa/CartItemRepository.java` | **New** | `findByCartIdAndProductId`, `findByCartId`, `deleteByCartId` |
| `repository/jpa/OrderItemRepository.java` | **New** | `findByOrderId` |
| `repository/jpa/OrderRepository.java` | **Updated** | Replaced `findByUserIdAndFlashSaleId` with `findByUserId(Page)`, `findExpiredOrderIds`, `cancelIfPending`, `payIfPending` |

### MongoDB Documents + Repositories created

| File | Collection |
|------|------------|
| `document/OrderActivityLog.java` | `order_activity_log` — orderId, userId, event, timestamp, metadata |
| `document/UserActionLog.java` | `user_action_log` — userId, action, timestamp, ip |
| `repository/mongo/OrderActivityLogRepository.java` | MongoRepository |
| `repository/mongo/UserActionLogRepository.java` | MongoRepository |

---

## ✅ Step 4 — Spring Security + Dual-Token JWT
**Status:** Complete · All Postman tests passed ✅

### Design
- **Access token** — 15 min, stateless, carries `sub` (username) + `role` claim
- **Refresh token** — 1 day, stateful; stored in Redis as `refresh:{jti} → userId` with matching TTL
- **Rotation** — `/refresh` deletes old `refresh:{jti}`, issues brand-new pair
- **Revocation** — `/logout` deletes `refresh:{jti}` immediately; 15m access token expires naturally

### Files created / updated

| File | Action | Notes |
|------|--------|-------|
| `security/JwtUtil.java` | **Updated** | Split into `generateAccessToken()` + `generateRefreshToken()` (with UUID jti); added `extractJti()`, `getRefreshExpirationMs()` |
| `security/JwtFilter.java` | Existed | No changes — validates access token on every request |
| `security/UserDetailsServiceImpl.java` | Existed | No changes |
| `security/config/SecurityConfig.java` | **Updated** | Removed old `/api/flash-sales` permit; added public rules for `/api/products/**`, `/api/categories/**` (GET), static assets |
| `service/RefreshTokenService.java` | **New** | Redis CRUD for `refresh:{jti}` — `store()`, `lookup()`, `revoke()` |
| `service/AuthService.java` | **Updated** | `register()` — creates user + cart in single `@Transactional`; `login()` — issues token pair; `refresh()` — validates jti in Redis then rotates; `logout()` — revokes jti |
| `controller/AuthController.java` | **Updated** | Added `POST /api/auth/refresh` and `POST /api/auth/logout` |
| `dto/AuthResponse.java` | **Updated** | Changed from `{token, username, role}` to `{accessToken, refreshToken}` |
| `dto/RefreshRequest.java` | **New** | `{ refreshToken: String }` |
| `exception/GlobalExceptionHandler.java` | **New** | Handles `ResourceNotFoundException` (404), `SoldOutException` (409), `RuntimeException` (400) |
| `exception/SoldOutException.java` | **New** | Carries `productId` |
| `exception/ResourceNotFoundException.java` | **New** | Standard 404 |

### Auth endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/auth/register` | Public | Creates user + cart (1 tx). Returns `{accessToken, refreshToken}` |
| POST | `/api/auth/login` | Public | Returns `{accessToken, refreshToken}` |
| POST | `/api/auth/refresh` | Public | Verifies jti in Redis, rotates pair |
| POST | `/api/auth/logout` | Public | Deletes `refresh:{jti}` from Redis |

---

## 🧹 Deep Cleanup — Legacy Flash-Sale Code Removed
**Date completed:** this session

The original codebase was bootstrapped around a "flash sale" concept that was dropped per the blueprint (Section 12: "Flash sale concept: **dropped**"). All legacy files were deleted or gutted.

### Files deleted (7)

| File | Reason |
|------|--------|
| `entity/FlashSale.java` | Flash sale concept dropped |
| `enums/FlashSaleStatus.java` | No longer referenced |
| `controller/FlashSaleController.java` | Concept dropped |
| `service/FlashSaleService.java` | Concept dropped |
| `repository/jpa/FlashSaleRepository.java` | Concept dropped |
| `dto/FlashSaleRequest.java` | Concept dropped |
| `dto/FlashSaleResponse.java` | Concept dropped |

### Files cleaned up

| File | Change |
|------|--------|
| `entity/Order.java` | Removed `flashSaleId`, added `totalAmount` |
| `repository/jpa/OrderRepository.java` | Removed `findByUserIdAndFlashSaleId`; added race-safe query methods |
| `kafka/event/OrderCreatedEvent.java` | Removed `flashSaleId`, `productName` |
| `kafka/event/PaymentCompletedEvent.java` | Removed `flashSaleId` |
| `kafka/producer/OrderEventProducer.java` | Key changed from `flashSaleId` to `orderId` |
| `kafka/consumer/OrderEventConsumer.java` | Stripped to log-only stub (DB/MongoDB logic deferred to Steps 9-10) |
| `service/OrderService.java` | Clean stub using `SecurityContextHolder`; `ResourceNotFoundException` for 404s |
| `controller/OrderController.java` | Removed `@RequestAttribute` hack; clean step-comment stubs |
| `dto/OrderResponse.java` | Removed `flashSaleId`, `productName`; added `totalAmount`, `paidAt` |

---

## 🧪 Postman Test Suite
**Status:** All 11 tests passed ✅

**Environment:** `flash-sale-system — local`  
**Collection:** `flash-sale-system — Steps 1-4`  
**Workspace:** Avery Hinazuki's Workspace (Postman)

### Folder 1 — Auth: Happy Path
| # | Request | Expected | Result |
|---|---------|----------|--------|
| 1 | POST /api/auth/register | 200 · saves tokens to env | ✅ |
| 2 | POST /api/auth/login | 200 · fresh token pair | ✅ |
| 3 | GET /api/orders/me (with token) | 200 · paginated empty list | ✅ |
| 4 | POST /api/auth/refresh | 200 · new pair ≠ old pair | ✅ |
| 5 | POST /api/auth/logout | 204 · empty body | ✅ |

### Folder 2 — Auth: Negative Cases
| # | Request | Expected | Result |
|---|---------|----------|--------|
| 6 | POST /register (duplicate username) | 400 · "already taken" | ✅ |
| 7 | POST /login (wrong password) | 401/403 | ✅ |
| 8 | GET /orders/me (no token) | 403 | ✅ |
| 9 | GET /orders/me (invalid JWT) | 403 | ✅ |
| 10 | POST /refresh (logged-out token) | 400 · "revoked" | ✅ |
| 11 | POST /refresh (rotated-out token) | 400 | ✅ |

---

---

## ✅ Step 5 — Category + Product CRUD + Inventory Adjust
**Status:** Complete · All Postman tests passed ✅

### Concepts introduced
- **`@PreAuthorize("hasRole('ADMIN')")`** — method-level security; public GETs need no token, admin writes require ADMIN role
- **Cache-aside read** — `GET /api/products/{id}` checks `product:{id}:detail` in Redis first; miss → MySQL → populate both `:detail` and `:stock` keys (TTL 60s each)
- **Delayed double deletion** — every stock write runs: delete cache → MySQL update → async second delete ~500ms later (kills stale entries a concurrent reader may have re-cached in the window)
- **`@Async`** — second deletion is fire-and-forget on `cacheEvictExecutor` thread pool; HTTP response returns immediately
- **Transactional product creation** — `POST /api/products` inserts `products` + `product_inventory` rows atomically; product never exists without inventory

### Files created

| File | Purpose |
|------|---------|
| `config/AsyncConfig.java` | `@EnableAsync` + `cacheEvictExecutor` thread pool (2–8 threads) |
| `service/ProductCacheService.java` | All Redis ops for `product:{id}:detail` + `product:{id}:stock`; owns `@Async scheduleSecondDeletion()` |
| `service/CategoryService.java` | List, create, update categories |
| `service/ProductService.java` | Full cache-aside reads, transactional create, catalog update, Redisson-locked inventory adjust |
| `controller/CategoryController.java` | 3 endpoints (GET public, POST/PUT admin) |
| `controller/ProductController.java` | 5 endpoints (GET list, GET detail, POST create, PUT update, PATCH inventory) |
| `dto/category/CategoryRequest.java` | `{ name, description }` |
| `dto/category/CategoryResponse.java` | `{ id, name, description }` |
| `dto/product/CreateProductRequest.java` | `{ name, description, price, categoryId, imageUrl, initialStock }` |
| `dto/product/UpdateProductRequest.java` | `{ name, description, price, categoryId, imageUrl, status }` |
| `dto/product/ProductResponse.java` | `{ id, name, description, price, categoryId, categoryName, imageUrl, status, availableStock, totalStock, createdAt }` |
| `dto/product/InventoryAdjustRequest.java` | `{ delta, reason: restock\|correction\|damaged }` |

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/categories` | Public | List all categories |
| POST | `/api/categories` | ADMIN | Create category |
| PUT | `/api/categories/{id}` | ADMIN | Update category |
| GET | `/api/products` | Public | Paginated list — `?category=`, `?search=`, `?page=`, `?size=` |
| GET | `/api/products/{id}` | Public | Detail + live stock (cache-aside) |
| POST | `/api/products` | ADMIN | Create product + inventory (one tx) |
| PUT | `/api/products/{id}` | ADMIN | Update catalog fields; `status=INACTIVE` soft-deletes |
| PATCH | `/api/products/{id}/inventory` | ADMIN | Adjust stock by delta under Redisson lock |

### Cache key design

| Key | Value | TTL |
|-----|-------|-----|
| `product:{id}:detail` | Full `ProductResponse` JSON | 60s |
| `product:{id}:stock` | `availableStock` integer string | 60s |
| `lock:product:{id}` | Redisson distributed lock | 10s lease |

### Bug fixed
- `adjustInventory` was missing `@Transactional` — `@Modifying` JPQL queries require an active transaction; added annotation to `ProductService.adjustInventory()`

### Postman test suite
**Collection:** `flash-sale-system — Steps 5-6` · 22 requests across 5 folders
**Environment:** `flash-sale-system — local`

| Folder | Requests | Result |
|--------|----------|--------|
| 0 — Pre-flight: Auth | User login, Admin login | ✅ |
| 1 — Categories | GET list, POST create, PUT update, 403 guard | ✅ |
| 2 — Products | Create, cache-miss/hit reads, list search, update, restock +5, verify stock=15, 404 | ✅ |
| 3 — Cart: Happy Path | Empty cart, add qty=2, upsert→qty=3, set qty=5, verify, delete, verify empty | ✅ |
| 4 — Cart: Soft Stock Check | Add qty=100 → 200 OK + STOCK_INSUFFICIENT warning | ✅ |
| 5 — Negative Cases | No-token cart/add → 403, wrong item → 404, user adjusting inventory → 403 | ✅ |

---

---

## ✅ Step 6 — Cart API
**Status:** Complete · All Postman tests passed ✅

### Design
- **Upsert on add** — `POST /api/cart/items` increments quantity if the product is already in the cart; creates a new row otherwise
- **Soft stock check** — quantity is always saved as requested; if `requestedQty > availableStock`, the response includes a `warning: { type: "STOCK_INSUFFICIENT", available, requested }` rather than rejecting the request
- **Ownership guard** — `PUT` and `DELETE` on `/items/{itemId}` verify the item belongs to the caller's cart before acting (returns 404 otherwise, no information leakage)
- **Live stock in GET** — `GET /api/cart` reads `availableStock` from MySQL for each item so the user sees fresh numbers

### Files created

| File | Purpose |
|------|---------|
| `dto/cart/CartItemRequest.java` | `{ productId, quantity (min=1) }` |
| `dto/cart/CartItemResponse.java` | `{ id, productId, productName, price, imageUrl, quantity, availableStock }` |
| `dto/cart/CartResponse.java` | `{ cartId, userId, items: List<CartItemResponse>, updatedAt }` |
| `dto/cart/StockWarning.java` | `{ type: "STOCK_INSUFFICIENT", available, requested }` |
| `dto/cart/AddToCartResponse.java` | `{ cartItem: CartItemResponse, warning: StockWarning? }` |
| `service/CartService.java` | getCart, addItem (upsert), updateItem, removeItem |
| `controller/CartController.java` | 4 endpoints, all require auth |

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/cart` | USER | Full cart with live stock per item |
| POST | `/api/cart/items` | USER | Add/increment item; soft stock check |
| PUT | `/api/cart/items/{itemId}` | USER | Set item quantity; soft stock check |
| DELETE | `/api/cart/items/{itemId}` | USER | Remove item; 204 No Content |

---

---

## ✅ Step 7 — Checkout (Sequential Deduction, No Lock)
**Status:** Complete

### Design
- **No Redisson lock** (Step 8 adds it) — demonstrates the race condition JMeter will expose
- **No cache invalidation** (Step 8 adds it) — Redis `:stock` / `:detail` keys can go stale during concurrent checkouts
- **MySQL conditional UPDATE** is the sole oversell guard: `WHERE available_stock >= qty`; `rowsAffected = 0` → `SoldOutException` (409)
- **`@Transactional` wraps the full method** in Step 7, so a `SoldOutException` mid-loop causes an automatic DB rollback of all prior deductions. Step 8 restructures: each deduction commits under its own per-product lock and manual compensation (`restoreStock`) handles the unwind.
- **Price snapshot** — product price is read once at cart-load time; `priceAtPurchase` on `OrderItem` is immune to later price changes
- **Publish-after-commit** — checkout and pay publish in-process `OrderCreatedDomainEvent` / `PaymentCompletedDomainEvent` inside the transaction; `OrderEventKafkaBridge` (`@TransactionalEventListener(AFTER_COMMIT)`) forwards to Kafka only after the DB commits. Bridge is a log-only stub in Step 7; Kafka wired in Step 9.

### Files created

| File | Purpose |
|------|---------|
| `dto/order/OrderItemResponse.java` | `{ id, productId, productName, quantity, priceAtPurchase, lineTotal }` |
| `kafka/event/OrderCreatedDomainEvent.java` | In-process Spring event — extends `ApplicationEvent`; carries `orderId, userId, createdAt` |
| `kafka/event/PaymentCompletedDomainEvent.java` | In-process Spring event — carries `orderId, userId, paidAt` |
| `kafka/producer/OrderEventKafkaBridge.java` | `@TransactionalEventListener(AFTER_COMMIT)` stub — logs only; KafkaTemplate wired in Step 9 |

### Files updated

| File | Change |
|------|--------|
| `dto/OrderResponse.java` | Added `List<OrderItemResponse> items` (null in list views, populated in detail view) |
| `repository/jpa/OrderRepository.java` | Added `clearAutomatically = true` to `payIfPending` and `cancelIfPending` so `findById` after a JPQL UPDATE sees fresh state |
| `service/OrderService.java` | Full `checkout()` + `pay()` + `getOrder()` (ownership check) implemented |
| `controller/OrderController.java` | Added `POST /checkout`, `POST /{id}/pay` |
| `exception/GlobalExceptionHandler.java` | Added `IllegalStateException` → 409 handler (pay-conflict case) |

### Endpoints

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/orders/checkout` | USER | Snapshot prices → sequential deductStock → create Order + OrderItems + clear cart → publish domain event |
| GET | `/api/orders/me` | USER | Caller's orders, paginated |
| GET | `/api/orders/{id}` | USER/ADMIN | Order detail with items; 404 for non-owners (no info leakage) |
| POST | `/api/orders/{id}/pay` | USER/ADMIN | Conditional UPDATE `WHERE status = PENDING`; 409 if already paid/cancelled |

### Checkout flow (Step 7 — no lock)

```
1. Resolve userId from JWT
2. Load cart items + snapshot { productId, qty, price } — validates ACTIVE status (400 if not)
3. Sequential deduction loop:
   FOR EACH item:
     UPDATE product_inventory SET available_stock = available_stock - qty
       WHERE product_id = ? AND available_stock >= qty
     rowsAffected = 0 → throw SoldOutException(productId)   → @Transactional rollback
4. Create Order (PENDING) + OrderItems (price_at_purchase snapshot) + clear cart  ← same tx
5. Publish OrderCreatedDomainEvent → bridge logs after commit (Step 9: Kafka send)
```

### Pay flow

```
UPDATE orders SET status = 'PAID', paid_at = NOW()
  WHERE id = ? AND status = 'PENDING'
rowsAffected = 0 → 409 (already paid or cancelled by expiry)
rowsAffected = 1 → publish PaymentCompletedDomainEvent → bridge logs after commit
```

### Why `@TransactionalEventListener(AFTER_COMMIT)`?
If a service published to Kafka *inside* the transaction and the transaction rolled back, the consumer would see an event for an order that never existed. `AFTER_COMMIT` guarantees the DB write is durable before any downstream system is notified. Conversely, if the tx commits but the JVM crashes before the Kafka send, the event is lost — the transactional outbox pattern is the production fix (out of scope, documented in README).

---

---

## ✅ Step 8 — Redisson Lock + Cache-Aside + Delayed Double Deletion in Checkout
**Status:** Complete

### What changed from Step 7

| Concern | Step 7 | Step 8 |
|---------|--------|--------|
| Outer `@Transactional` on `checkout()` | ✅ one big tx, auto rollback | ❌ removed — each deduction is its own tx |
| Per-product Redisson lock | ❌ none | ✅ `lock:product:{id}` (5s wait, 10s lease) |
| Cache first-deletion (before MySQL write) | ❌ none | ✅ `cacheService.deleteCache(productId)` |
| Async second-deletion (~500ms later) | ❌ none | ✅ `cacheService.scheduleSecondDeletion(productId)` |
| Compensation on failure | Automatic via tx rollback | Manual `restoreStock` + cache invalidation |

### Why no outer `@Transactional` on `checkout()`

Each deduction commits independently under its own per-product lock. If the order-creation step fails *after* some deductions have already been committed, a `@Transactional` rollback on the outer method cannot undo those committed writes — only explicit `restoreStock` calls can. Removing the outer transaction also maximises concurrency: checkouts of different products never block each other at the DB-transaction level.

### Transaction structure

```
checkout()  — no @Transactional
  │
  ├─ self.loadCartSnapshot()           @Transactional(readOnly=true)
  │    Lazy-loads Product for each CartItem; validates ACTIVE; snapshots prices
  │
  ├─ FOR EACH item:
  │    acquire lock:product:{id}
  │    deleteCache(id)                 ← first deletion
  │    inventoryRepository.deductStock()  @Transactional on repo method
  │    release lock
  │    scheduleSecondDeletion(id)      ← async ~500ms, @Async("cacheEvictExecutor")
  │
  ├─ self.persistOrder()               @Transactional
  │    save Order + OrderItems + deleteByCartId + publishEvent
  │
  └─ CATCH any exception:
       FOR EACH committed deduction:
         deleteCache(id)
         restoreStock(id, qty)         @Transactional on repo method
         scheduleSecondDeletion(id)
       re-throw
```

### Self-injection pattern

`loadCartSnapshot()` and `persistOrder()` are `@Transactional` methods on `OrderService` itself. Calling `this.method()` bypasses Spring's AOP proxy — the transaction annotation is ignored. Solution: inject the proxy back as a field:

```java
@Lazy @Autowired
private OrderService self;
```

`@Lazy` prevents a circular dependency during bean construction. Calling `self.loadCartSnapshot()` and `self.persistOrder()` goes through the proxy and honours `@Transactional`. This is the standard Spring idiom for transactional self-calls.

### Files changed

| File | Change |
|------|--------|
| `repository/jpa/ProductInventoryRepository.java` | Added `@Transactional` to `deductStock` and `restoreStock` — each call commits its own short tx when invoked from a non-transactional context |
| `service/OrderService.java` | Removed outer `@Transactional` from `checkout()`; added `RedissonClient`, `ProductCacheService`, `ProductRepository` dependencies; added `@Lazy @Autowired OrderService self`; added `loadCartSnapshot()` + `persistOrder()` helper methods; rewrote deduction loop with lock + cache-aside; added manual compensation block |

### Residual gap (documented)
If the compensation block itself fails (MySQL unreachable), deducted stock leaks. The production fix is a durable compensation queue / transactional outbox pattern — out of scope but called out in README.

---

---

## ✅ Step 9 — Kafka Async Pipeline (order-created + payment-completed)
**Status:** Complete

### What was wired

The publish-after-commit infrastructure was already in place from Steps 7–8 (domain events + bridge stub + consumer skeleton). Step 9 replaces the bridge stubs with actual Kafka sends.

### Full event flow (end-to-end)

```
checkout() / pay()
  │
  ├─ DB writes commit  (@Transactional)
  │
  ├─ ApplicationEventPublisher.publishEvent(OrderCreatedDomainEvent)
  │   or publishEvent(PaymentCompletedDomainEvent)
  │
  └─ OrderEventKafkaBridge  (@TransactionalEventListener AFTER_COMMIT)
       Fires ONLY after the DB transaction is durable.
       Calls OrderEventProducer.sendOrderCreatedEvent()
         or OrderEventProducer.sendPaymentCompletedEvent()
       which sends to Kafka asynchronously via KafkaTemplate.
       └─ KafkaTemplate.send() returns a CompletableFuture;
          success/failure logged by whenComplete callback.

OrderEventConsumer  (@KafkaListener)
  ├─ handleOrderCreated()     → logs orderId, userId, createdAt
  └─ handlePaymentCompleted() → logs orderId, userId, paidAt
  (Step 10 adds MongoDB writes here)
```

### Why the consumer does NOT update order status

An earlier design comment suggested the consumer should drive `PENDING → PAID`. That's been removed. The `/pay` endpoint already performs the conditional UPDATE synchronously before publishing the event — the DB is already `PAID` by the time the consumer receives the message. The consumer's role is downstream processing (logging, notifications), not state mutation.

### Files changed

| File | Change |
|------|--------|
| `kafka/producer/OrderEventKafkaBridge.java` | Injected `OrderEventProducer`; replaced log-only stubs with `sendOrderCreatedEvent()` and `sendPaymentCompletedEvent()` calls |
| `kafka/consumer/OrderEventConsumer.java` | Removed stale TODO about status update; cleaned up log format; added clear Step 10 placeholders |

### Residual gap (documented)
If the JVM crashes between the DB commit and the Kafka send, the event is lost. The transactional outbox pattern (write event to an `outbox` table in the same tx, separate poller publishes to Kafka) is the production fix — out of scope, will be noted in README.

### How to verify
Restart the app and run a checkout + pay. In the Spring Boot logs you should see:

```
[Bridge] Forwarding order-created to Kafka: orderId=X
[Kafka] order-created sent: orderId=X partition=N offset=M
[Kafka][order-created] orderId=X userId=Y createdAt=...

[Bridge] Forwarding payment-completed to Kafka: orderId=X
[Kafka] payment-completed sent: orderId=X partition=N offset=M
[Kafka][payment-completed] orderId=X userId=Y paidAt=...
```

---

---

## ✅ Step 10 — MongoDB Logging (order_activity_log + user_action_log)
**Status:** Complete

### What was added

Two MongoDB write paths:

**1. Order activity log (via Kafka consumer)**
Each Kafka event now persists a document to `order_activity_log` in addition to logging to the console. The Kafka consumer is the right place for this — it already receives the event after the DB transaction commits, keeping the MongoDB write fully decoupled from the HTTP request path.

**2. User action log (via servlet filter)**
`UserActionLogFilter` runs inside the Spring Security filter chain, after `JwtFilter`. For every authenticated request it fires a fire-and-forget async write to `user_action_log` via `UserActionLogService`. Anonymous requests (public product browsing) are skipped — no `userId` to associate.

### Design decisions

| Decision | Rationale |
|----------|-----------|
| `UserActionLogFilter` is NOT `@Component` | `@Component` causes Spring Boot to auto-register the filter as a standalone servlet filter AND it gets added to the security chain — it would fire twice per request. Creating it via `new` in `SecurityConfig` and adding via `addFilterAfter` means exactly one registration inside the security chain. |
| `addFilterAfter(…, JwtFilter.class)` | Guarantees `SecurityContextHolder` already has the resolved `Authentication` when the filter runs. |
| `filterChain.doFilter()` first, then log | The log entry is written after the request is processed. The async write has zero impact on response latency. |
| `@Async("cacheEvictExecutor")` on `UserActionLogService.logAsync()` | Reuses the existing thread pool (2–8 threads, queue=200) rather than adding a second pool for a lightweight fire-and-forget task. |
| `UserRepository.findByUsername()` inside async | Resolves `Long userId` from the JWT `sub` claim (username) without coupling the filter to the JPA layer directly. |

### Event flow — order_activity_log

```
checkout() → Kafka order-created → OrderEventConsumer.handleOrderCreated()
  └─ activityLogRepository.save({ orderId, userId, event: "ORDER_CREATED", timestamp: createdAt })

pay() → Kafka payment-completed → OrderEventConsumer.handlePaymentCompleted()
  └─ activityLogRepository.save({ orderId, userId, event: "PAYMENT_COMPLETED", timestamp: paidAt })
```

### Event flow — user_action_log

```
Any authenticated HTTP request
  │
  ├─ JwtFilter            → sets SecurityContextHolder
  ├─ UserActionLogFilter  → runs chain first, then:
  │    SecurityContextHolder.getAuthentication()
  │    if authenticated (not anonymous):
  │      userActionLogService.logAsync(username, "METHOD /path", remoteAddr)
  │        └─ @Async: userRepository.findByUsername(username)
  │                   → userActionLogRepository.save({ userId, action, timestamp, ip })
  └─ (HTTP response already sent)
```

### Files changed

| File | Change |
|------|--------|
| `kafka/consumer/OrderEventConsumer.java` | Injected `OrderActivityLogRepository`; writes `OrderActivityLog` document on ORDER_CREATED and PAYMENT_COMPLETED events |
| `service/UserActionLogService.java` | **New** — `@Async("cacheEvictExecutor")` service; resolves `userId` from username, saves `UserActionLog` to MongoDB |
| `filter/UserActionLogFilter.java` | **New** — `OncePerRequestFilter`; runs after JwtFilter in security chain; fires async log for authenticated requests |
| `security/config/SecurityConfig.java` | Injected `UserActionLogService`; instantiates `UserActionLogFilter` and registers with `addFilterAfter(…, JwtFilter.class)` |

### How to verify

After restart, do a checkout + pay with a logged-in user. Then query MongoDB:

```js
// In mongosh (connects to flash-mongo container):
use flash_sale
db.order_activity_log.find().sort({ timestamp: -1 }).limit(5)
db.user_action_log.find().sort({ timestamp: -1 }).limit(10)
```

Expected documents:
```json
// order_activity_log
{ "orderId": 57, "userId": 53, "event": "ORDER_CREATED",      "timestamp": "2026-05-25T..." }
{ "orderId": 57, "userId": 53, "event": "PAYMENT_COMPLETED",  "timestamp": "2026-05-25T..." }

// user_action_log
{ "userId": 53, "action": "POST /api/orders/checkout", "timestamp": "...", "ip": "127.0.0.1" }
{ "userId": 53, "action": "POST /api/orders/57/pay",   "timestamp": "...", "ip": "127.0.0.1" }
```

---

---

## ✅ Step 11 — OrderExpiryScheduler + GET /api/orders [ADMIN]
**Status:** Complete

### What was added

**`OrderExpiryScheduler`** — a `@Scheduled` background job that runs every `expiry-job-interval-seconds` (60 s by default) with a 30 s startup delay. It scans for PENDING orders older than `pending-timeout-minutes` (15 min) in batches of up to `expiry-job-batch-size` (100), and for each:

1. **Atomically claims the cancellation** via `cancelIfPending` — a conditional UPDATE (`WHERE status = 'PENDING'`). If `rowsAffected = 0`, the user paid in the same instant; skip entirely. This is the race guard between `/pay` and the expiry job — exactly one of the two wins.
2. **Loads userId + item data** via lightweight queries (no lazy loading).
3. **Restores stock per item** under the same `lock:product:{id}` Redisson lock used by checkout and admin inventory PATCH — so the restore cannot race with a concurrent checkout on the same product. Full cache-aside applied: first-deletion → `restoreStock` → second-deletion (async).
4. **Writes `EXPIRED_CANCELLED`** to MongoDB `order_activity_log` (best-effort — failure never aborts the job).

**`GET /api/orders` [ADMIN]** — paginated list of all orders across all users. Guarded by `@PreAuthorize("hasRole('ADMIN')")` in `OrderService.getAllOrders()`.

### Race guard — /pay vs expiry job

```
Timeline A — user pays before expiry fires:
  /pay: payIfPending(orderId)  → rowsAffected=1  → PAID
  job:  cancelIfPending(orderId) → rowsAffected=0  → skipped (order already PAID)

Timeline B — expiry fires before user pays:
  job:  cancelIfPending(orderId) → rowsAffected=1  → CANCELLED; stock restored
  /pay: payIfPending(orderId)  → rowsAffected=0  → 409 to user ("already cancelled")
```

The conditional UPDATE is the atomicity primitive. No locking between `/pay` and the expiry job is needed — MySQL row-level locking on the UPDATE ensures exactly one writer wins.

### Scheduler config (already in application.yml)

```yaml
app:
  order:
    pending-timeout-minutes: 15
    expiry-job-interval-seconds: 60
    expiry-job-batch-size: 100
```

### Files changed

| File | Change |
|------|--------|
| `config/AsyncConfig.java` | Added `@EnableScheduling` |
| `repository/jpa/OrderRepository.java` | Added `@Transactional` to `cancelIfPending` — allows the scheduler (non-transactional context) to call it without a "no active transaction" error |
| `repository/jpa/OrderItemRepository.java` | Added `findProductIdAndQuantityByOrderId` — JPQL projection query returning `[productId, quantity]` pairs; avoids LazyInitializationException when called outside a Hibernate session |
| `scheduler/OrderExpiryScheduler.java` | **New** — full expiry job (see design above) |
| `service/OrderService.java` | Added `getAllOrders(Pageable)` with `@PreAuthorize("hasRole('ADMIN')")` |
| `controller/OrderController.java` | Added `GET /api/orders` → `getAllOrders()` |

### How to verify

**Expiry job:** temporarily set `pending-timeout-minutes: 0` in `application.yml` and restart. After ~30 s the scheduler fires. Any PENDING order immediately hits the cutoff — you should see:
```
[Expiry] Found N expired PENDING order(s) — processing
[Expiry] Cancelled orderId=X
[Expiry] Restored productId=Y qty=Z for orderId=X
```
And in MongoDB:
```js
db.order_activity_log.find({ event: "EXPIRED_CANCELLED" })
```

**Admin endpoint:** login as admin and call `GET /api/orders` — returns all orders paginated. Non-admin gets 403.

---

## 📋 Remaining Steps

| Step | What | Status |
|------|------|--------|
| 8 | Redisson lock + cache-aside + delayed double deletion | ✅ Complete |
| 9 | Kafka — async order-created + payment-completed pipeline | ✅ Complete |
| 10 | MongoDB — order_activity_log + user_action_log filter | ✅ Complete |
| 11 | OrderExpiryScheduler + GET /api/orders [ADMIN] | ✅ Complete |
| 12 | Vanilla JS frontend | ⬜ Pending |
| 13 | JMeter stress test + README with benchmark results | ⬜ Pending |
