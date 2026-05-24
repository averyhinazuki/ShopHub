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

## 📋 Remaining Steps

| Step | What | Status |
|------|------|--------|
| 8 | Redisson lock + cache-aside + delayed double deletion | ⬜ Pending |
| 9 | Kafka — async order-created + payment-completed pipeline | ⬜ Pending |
| 10 | MongoDB — order_activity_log + user_action_log filter | ⬜ Pending |
| 11 | OrderExpiryScheduler + conditional UPDATE on /pay | ⬜ Pending |
| 12 | Vanilla JS frontend | ⬜ Pending |
| 13 | JMeter stress test + README with benchmark results | ⬜ Pending |
