# ShopHub

A full-stack online shopping platform engineered for high-concurrency checkout under contention. Focuses on correctness when many users race for limited stock — distributed locking, cache-aside with double-deletion, lock-free order expiry, and a publish-after-commit event pipeline.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Backend | Java 19 · Spring Boot 3 · Spring Security |
| Frontend | Vue 3 · Vite · Tailwind CSS |
| Primary DB | MySQL 8 (JPA/Hibernate) |
| Cache | Redis (Redisson distributed locks) |
| Message Broker | Apache Kafka |
| Audit Logs | MongoDB |
| Image Storage | Cloudinary |
| Auth | JWT (access + refresh token rotation) |

---

## Architecture Highlights

### Inventory Under Contention

Popular products create write storms during product launches, sales, or viral moments. Every stock deduction goes through a **per-product Redisson distributed lock** (`lock:product:{id}`), ensuring no two concurrent checkouts can oversell the same item. The same lock key is shared by checkout, admin inventory patches, and the order expiry scheduler — so all writers are serialized on the same mutex regardless of origin.

A MySQL **conditional `UPDATE ... WHERE stock >= ?`** sits behind the lock as a last-line consistency guarantee: even if the lock layer were bypassed or Redis became unavailable, the database itself refuses to commit an oversell. Redis serves only as a cache — MySQL remains the source of truth.

### Cache-Aside with Double-Deletion

Product data is cached in Redis with a 60-second TTL. On any write (checkout, restock, status change), the system uses the **double-deletion pattern** to eliminate stale reads:

1. First cache deletion — before the MySQL write
2. MySQL write commits
3. Async second deletion — evicts any entry re-cached by a concurrent reader in the write window

This closes the race between the write path and readers that might re-populate the cache between deletion and commit.

### Lock-Free Order Expiry

Unpaid orders hold stock. A scheduled job scans for PENDING orders older than the configured timeout and cancels them, restoring stock. The cancellation uses a **conditional UPDATE**:

```sql
UPDATE orders SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'
```

If `/pay` wins first, `rowsAffected = 0` and the scheduler skips — stock stays deducted. If the scheduler wins first, a subsequent `/pay` gets a 409. Exactly one path commits, both are inherently **idempotent**, and no separate coordination (lock, leader election, distributed transaction) is needed.

### Event-Driven Order Flow

Order creation and payment completion publish domain events to **Kafka** (`order-created`, `payment-completed` topics) using a **publish-after-commit** pattern — events fire only after the DB transaction succeeds, preventing phantom emissions on rollback. Consumers handle downstream processing asynchronously, decoupling the write path from side effects.

### JWT Auth with Refresh Token Rotation

- Access tokens: 15-minute expiry, carry username + role
- Refresh tokens: 1-day expiry, stored in Redis keyed by `jti` (UUID), enabling individual revocation
- On refresh: old token is revoked, a new pair is issued (rotation)
- Logout invalidates the refresh token from Redis — access tokens expire naturally

The frontend uses an Axios response interceptor to transparently call `/auth/refresh` on 401 and retry the original request with the new token.

### Audit & Observability

All authenticated HTTP requests are logged to **MongoDB** via a post-JWT filter (`UserActionLogFilter`), capturing username, method, path, and timestamp. Order lifecycle events (checkout, payment, expiry cancellation) write to a separate `OrderActivityLog` collection.

---

## Validation

The concurrency design is verified end-to-end with JMeter (see `jmeter/checkout-preauth-latency-test.jmx`).

**Stress test — 5000 concurrent pre-authenticated checkouts against a 10-unit SKU:**

| Metric | Result |
|---|---|
| Successful checkouts | **10** (exactly the stock limit) |
| Graceful 409 rejections | 4990 |
| Oversells | **0** |
| Connection failures | 0 |
| Successful checkout latency | 84 – 422 ms (median ~280 ms) |
| Total test duration | 30.9 s |

Reproduce with:

```bash
# 1. Pre-register 5000 users and populate carts
python jmeter/setup.py

# 2. Run the test (within JWT TTL)
jmeter -n -t jmeter/checkout-preauth-latency-test.jmx -l results.jtl -e -o report/
```

The tearDown asserts `availableStock = 0` — any oversell fails the test loudly.

---

## Features

**Storefront**
- Browse products with category filter and search
- Add to cart, adjust quantities
- Checkout → pay flow with real-time stock validation
- Order history

**Admin Panel**
- Create and manage products with Cloudinary image upload
- Live inventory restock with distributed-lock safety
- Order management with status tracking

**Infrastructure**
- Stateless REST API (JWT, no sessions)
- Redis-backed distributed locks via Redisson
- Kafka async event pipeline
- Scheduled order expiry with batch processing
- MongoDB audit trail

---

## Running Locally

**Prerequisites:** Java 19, Node 18+, MySQL 8, Redis, MongoDB, Kafka, Zookeeper

```bash
# Backend
./mvnw spring-boot:run

# Frontend
cd frontend
npm install
npm run dev
```

Configure credentials in `src/main/resources/application.yml`:

```yaml
spring.datasource:
  url: jdbc:mysql://localhost:3306/flash_sale_db
  username: root
  password: yourpassword

app:
  jwt.secret: your-256-bit-secret
  cloudinary:
    cloud-name: your-cloud-name
    api-key: your-api-key
    api-secret: your-api-secret
```

---

## API Overview

| Method | Path | Auth | Description |
|---|---|---|---|
| POST | `/api/auth/register` | Public | Register |
| POST | `/api/auth/login` | Public | Login |
| POST | `/api/auth/refresh` | Public | Rotate token pair |
| POST | `/api/auth/logout` | User | Revoke refresh token |
| GET | `/api/products` | Public | List products (paginated, filterable) |
| GET | `/api/products/{id}` | Public | Product detail (cache-aside) |
| POST | `/api/products` | Admin | Create product + inventory |
| PUT | `/api/products/{id}` | Admin | Update product |
| PATCH | `/api/products/{id}/inventory` | Admin | Adjust stock |
| GET | `/api/cart` | User | Get cart |
| POST | `/api/cart/items` | User | Add to cart |
| DELETE | `/api/cart/items/{id}` | User | Remove from cart |
| POST | `/api/orders/checkout` | User | Checkout cart |
| POST | `/api/orders/{id}/pay` | User | Pay for order |
| GET | `/api/orders` | User/Admin | List orders |
| POST | `/api/upload` | Admin | Upload image to Cloudinary |

---

## Project Structure

```
src/main/java/com/example/flashsale/
├── controller/        # REST endpoints
├── service/           # Business logic
├── entity/            # JPA entities
├── dto/               # Request/response shapes
├── security/          # JWT filter, util, Spring Security config
├── kafka/             # Producers, consumers, domain events
├── scheduler/         # Order expiry job
├── filter/            # UserActionLog filter
├── repository/
│   ├── jpa/           # MySQL repositories
│   └── mongo/         # MongoDB repositories
├── document/          # MongoDB documents
├── exception/         # Global exception handler
└── enums/             # OrderStatus, ProductStatus, Role

frontend/src/
├── views/             # Page components (Home, Cart, Orders, Admin)
├── stores/            # Pinia auth store
├── services/          # Axios instance + interceptors
└── router/            # Vue Router with auth guards
```
