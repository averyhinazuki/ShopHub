# Flash Sale System

High-concurrency e-commerce backend demonstrating production-grade inventory control under concurrent load.

**Stack:** Java 19 · Spring Boot 3.x · MySQL 8 · Redis 7 · Redisson · Kafka · MongoDB · Docker Compose

---

## Architecture Overview

```
Client
  │
  ▼
Spring Boot REST API (port 8080)
  ├── MySQL 8         — transactional source of truth (products, inventory, orders)
  ├── Redis 7         — read cache only (cache-aside, NOT stock authority)
  │   └── Redisson    — distributed lock per product (lock:product:{id})
  ├── Kafka           — async order/payment event pipeline
  └── MongoDB 7       — activity logs (order_activity_log, user_action_log)
```

### Key Concurrency Design

| Concern | Mechanism |
|---------|-----------|
| Oversell prevention | `UPDATE product_inventory SET available_stock = available_stock - ? WHERE product_id = ? AND available_stock >= ?` — rowsAffected=0 → SoldOutException |
| Serialization | Redisson distributed lock `lock:product:{id}` — shared by checkout, expiry restore, and admin inventory PATCH |
| Cache consistency | Cache-aside + delayed double deletion (first delete before MySQL write, async second delete ~500ms after) |
| Atomic order expiry | Conditional UPDATE `WHERE status = 'PENDING'` — exactly one of {pay, expire} wins |
| Publish-after-commit | In-process Spring domain events + `@TransactionalEventListener(AFTER_COMMIT)` before Kafka send |

---

## Quick Start

### 1. Start infrastructure

```bash
docker-compose -f src/main/resources/docker-compose.yml up -d
```

Services: MongoDB (27017), Redis (6379), Zookeeper (2181), Kafka (9092).
MySQL runs locally on port 3306 — create database `flash_sale` before starting.

### 2. Run the app

```bash
./mvnw spring-boot:run
```

Spring Boot auto-creates all tables via `ddl-auto: update`.

### 3. Seed an ADMIN user

Register a user via the API, then manually update their role in MySQL:

```sql
UPDATE users SET role = 'ADMIN' WHERE username = 'admin';
```

---

## API Reference

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/auth/register` | Public | Create user + cart (one tx) |
| POST | `/api/auth/login` | Public | Returns `{accessToken, refreshToken}` |
| POST | `/api/auth/refresh` | Public | Rotate token pair |
| POST | `/api/auth/logout` | Public | Revoke refresh token |
| GET | `/api/categories` | Public | List categories |
| POST | `/api/categories` | ADMIN | Create category |
| PUT | `/api/categories/{id}` | ADMIN | Update category |
| GET | `/api/products` | Public | Paginated list (`?category=`, `?search=`) |
| GET | `/api/products/{id}` | Public | Detail + live stock (cache-aside) |
| POST | `/api/products` | ADMIN | Create product + inventory (one tx) |
| PUT | `/api/products/{id}` | ADMIN | Update catalog; `status=INACTIVE` = soft-delete |
| PATCH | `/api/products/{id}/inventory` | ADMIN | Adjust stock by delta |
| GET | `/api/cart` | USER | Full cart with live stock |
| POST | `/api/cart/items` | USER | Add/increment item |
| PUT | `/api/cart/items/{itemId}` | USER | Set item quantity |
| DELETE | `/api/cart/items/{itemId}` | USER | Remove item |
| POST | `/api/orders/checkout` | USER | Checkout cart (hot path) |
| GET | `/api/orders/me` | USER | My orders (paginated) |
| GET | `/api/orders/{id}` | USER/ADMIN | Order detail |
| POST | `/api/orders/{id}/pay` | USER/ADMIN | Mock payment |
| GET | `/api/orders` | ADMIN | All orders (paginated) |

---

## Checkout Hot Path

```
POST /api/orders/checkout

1. Resolve userId from JWT
2. Load + snapshot cart { productId, qty, price, status }
3. Reject if any product.status != ACTIVE (400)
4. FOR EACH item:
   a. Acquire Redisson lock:product:{id}   (5s wait, 10s lease)
   b. Delete Redis cache (first deletion)
   c. UPDATE product_inventory SET available_stock = available_stock - qty
        WHERE product_id = ? AND available_stock >= qty
      rowsAffected=0 → SoldOutException (409)
   d. Release lock
   e. Schedule async second cache deletion (~500ms)
5. @Transactional: create Order + OrderItems + clear cart + publish domain event
6. @TransactionalEventListener(AFTER_COMMIT) → Kafka send
7. CATCH any exception → restore all deducted stock (same lock + cache invalidation)
```

**Known residual gap:** if compensation fails (MySQL unreachable), stock leaks. Production fix: durable compensation queue / transactional outbox pattern.

---

## JMeter Stress Test

### What it proves

50 concurrent users each hold a cart with 1 unit of a product that has only **10 units in stock**. All 50 hit `POST /api/orders/checkout` simultaneously. Expected outcome:

- Exactly **10 HTTP 200** (checkout succeeded)
- Exactly **40 HTTP 409** (sold out — `SoldOutException`)
- Final `available_stock` in MySQL = **0** (never negative)

### Requirements

- Apache JMeter 5.4+ with Groovy scripting support
- Spring Boot app running on `localhost:8080`
- An ADMIN user in the database

### Run the test

```bash
# From project root
# 1. Edit jmeter/checkout-stress-test.jmx — set PRODUCT_ID, ADMIN_USERNAME, ADMIN_PASSWORD
#    in the User Defined Variables section.

# 2. Run in non-GUI mode
jmeter -n -t jmeter/checkout-stress-test.jmx -l jmeter/results/results.jtl -e -o jmeter/results/report/

# 3. View HTML report
open jmeter/results/report/index.html
```

### Test plan structure

| Thread Group | Threads | Purpose |
|---|---|---|
| setUp | 1 | Admin login → reset product stock to `INITIAL_STOCK` |
| Concurrent Checkout | 50 | Register user → add to cart → POST /checkout |
| tearDown | 1 | GET final stock → log OVERSELL / NO OVERSELL verdict |

`TestPlan.serialize_threadgroups=true` ensures setUp → main → tearDown ordering.

The main thread group uses JMeter's `SyncTimer` equivalent via sequential serialization, so all 50 checkout requests are submitted in rapid succession within the 5s ramp window.

### Benchmark Results

> Fill in after running the test.

| Metric | Value |
|--------|-------|
| Concurrent users | 200 |
| Initial stock | 10 |
| HTTP 200 (success) | **10** |
| HTTP 409 (sold out) | **190** |
| Final available_stock | **0** |
| Oversell? | **NO** |
| Min checkout latency | 9ms |
| Avg checkout latency | 14ms |
| P90 checkout latency | 20ms |
| P99 checkout latency | 24ms |
| Max checkout latency | 26ms |

---

## Known Limitations / Production Next Steps

1. **Transactional outbox pattern** — if the JVM crashes between DB commit and Kafka send, the event is lost. Fix: write event to an `outbox` table in the same tx; separate poller publishes to Kafka.
2. **Compensation queue** — if stock compensation fails (MySQL unreachable during checkout rollback), deducted stock leaks. Fix: persist the rollback list to a durable queue and retry.
3. **JWT secret in config** — kept in `application.yml` for this dev build. Production: load from environment variable / secret manager.
4. **Single Kafka broker** — `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1` is dev-only. Production: 3+ brokers, replication factor 3.
