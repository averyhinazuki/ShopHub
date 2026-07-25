# ShopHub

A full-stack online shopping platform engineered for high-concurrency checkout under contention. Focuses on correctness when many users race for limited stock — Kafka-buffered async checkout, distributed locking, idempotent consumers with dead-letter retry, cache-aside with double-deletion, lock-free order expiry, and a publish-after-commit event pipeline.

---

## Tech Stack

| Layer | Technology |
| :--- | :--- |
| Backend | Java 21 · Spring Boot 3 · Spring Security |
| Frontend | Vue 3 · Vite · Tailwind CSS |
| Primary DB | MySQL 8 (JPA/Hibernate · Flyway migrations) |
| Cache | Redis (Redisson distributed locks) |
| Message Broker | Apache Kafka |
| Audit Logs | MongoDB |
| Image Storage | Cloudinary |
| Auth | JWT (access + refresh token rotation) |
| Monitoring | Prometheus · Grafana (Micrometer / Actuator) |

---

## Architecture Highlights

### Async Checkout — Kafka as a Load Buffer

A flash sale turns checkout into a thundering herd: thousands of requests hit the same SKU in the same second, and each one wants a row lock. Instead of letting that spike land on MySQL directly, `POST /checkout` does no database work at all — it publishes a `checkout-requested` event to Kafka and immediately returns **202 Accepted** with a `checkoutId`:

```
POST /checkout  →  202 { checkoutId, status: PENDING }
                          │
                          ▼  client polls
GET /checkout-status/{checkoutId}  →  { status: SUCCESS, orderId } | { status: FAILED, failureReason }
```

A consumer drains the topic at a sustainable rate and runs the real checkout (lock → deduct → persist), writing the outcome to Redis (30-minute TTL) for the client to poll. The web tier absorbs the spike at Kafka-append speed; MySQL sees a steady stream instead of 10k concurrent lock acquisitions.

### Idempotent Consumers

Kafka guarantees **at-least-once** delivery — a consumer crash after processing but before the offset commit means redelivery. Every consumer therefore checks a Redis dedup key (`kafka:processed:{topic}:{key}`, 24h TTL) before processing and marks it after. A redelivered `checkout-requested` event is skipped instead of charging the user twice.

### Retry with Backoff + Dead Letter Topics

Failures are classified, not swallowed:

- **Terminal failures** (sold out) — no retry; the consumer writes `FAILED` to Redis immediately so the client gets a fast answer.
- **Transient failures** (DB down, lock timeout) — `@RetryableTopic` retries up to 3 times with exponential backoff (1s → 2s → 4s) on dedicated retry topics, so a poison message never blocks the main partition.
- **Exhausted retries** — the message lands on a dead letter topic; the `@DltHandler` writes `FAILED` to Redis (guarding against overwriting an already-recorded `SUCCESS`) so the user is never left polling forever, and the DLT retains the message for manual inspection.

### Inventory Under Contention

Popular products create write storms during launches, sales, or viral moments. Every stock deduction goes through a **per-product Redisson distributed lock** (`lock:product:{id}`), ensuring no two concurrent checkouts can oversell the same item. The same lock key is shared by checkout, admin inventory patches, and the order expiry scheduler — so all writers are serialized on the same mutex regardless of origin.

A MySQL **conditional `UPDATE ... WHERE stock >= ?`** sits behind the lock as a last-line consistency guarantee: even if the lock layer were bypassed or Redis became unavailable, the database itself refuses to commit an oversell. Redis serves only as a cache — MySQL remains the source of truth.

### Cache-Aside with Double-Deletion

Product data is cached in Redis with a 60-second TTL. On any write (checkout, restock, status change), the system uses the **double-deletion pattern** to eliminate stale reads:

1. First cache deletion — before the MySQL write
2. MySQL write commits
3. Async second deletion (~500 ms later) — evicts any entry re-cached by a concurrent reader in the write window

This closes the race between the write path and readers that might re-populate the cache between deletion and commit.

### Lock-Free Order Expiry

Unpaid orders hold stock. A scheduled job scans for PENDING orders older than 15 minutes and cancels them, restoring stock. The cancellation uses a **conditional UPDATE**:

```sql
UPDATE orders SET status = 'CANCELLED' WHERE id = ? AND status = 'PENDING'
```

If `/pay` wins first, `rowsAffected = 0` and the scheduler skips — stock stays deducted. If the scheduler wins first, a subsequent `/pay` gets a 409. Exactly one path commits, both are inherently **idempotent**, and no separate coordination (lock, leader election, distributed transaction) is needed.

### Event-Driven Order Flow

Order creation and payment completion publish domain events to **Kafka** (`order-created`, `payment-completed` topics) using a **publish-after-commit** pattern — events fire only after the DB transaction succeeds, preventing phantom emissions on rollback. Consumers handle downstream processing asynchronously (with the same idempotency + DLT protections as checkout), decoupling the write path from side effects.

**Acknowledged gap:** `AFTER_COMMIT` is not a full delivery guarantee. A JVM crash in the window between DB commit and the Kafka send permanently loses the event. The complete solution is the transactional outbox pattern (write the event as a DB row in the same transaction, then poll and publish). This is intentionally out of scope for this build.

### JWT Auth with Refresh Token Rotation

- Access tokens: 5-minute expiry, carry username + role
- Refresh tokens: 1-day expiry, stored in Redis keyed by `jti` (UUID), enabling individual revocation
- On refresh: old token is revoked, a new pair is issued (rotation)
- Logout invalidates the refresh token from Redis — access tokens expire naturally

The frontend uses an Axios response interceptor to transparently call `/auth/refresh` on 401 and retry the original request with the new token.

### Audit & Observability

All authenticated HTTP requests are logged to **MongoDB** via a post-JWT filter (`UserActionLogFilter`), capturing username, method, path, and timestamp. Order lifecycle events (checkout, payment, expiry cancellation) write to a separate `OrderActivityLog` collection via a Kafka consumer.

Both MongoDB write paths are **best-effort**: exceptions are caught and swallowed. A MongoDB outage must never fail an order creation or payment — logs are observability data, not business-critical state.

---

## Concurrency Stress Test

Verified with JMeter against **5000 concurrent pre-authenticated checkouts on a 10-unit SKU** — first the synchronous design, then the async pipeline that replaced it. Both hold the oversell invariant (teardown asserts `availableStock = 0`; any oversell fails loudly).

### Synchronous (`jmeter/checkout-preauth-latency-test.jmx` → `results-preauth.jtl`)

| Metric | Result |
| :--- | :--- |
| Successful checkouts | **10** (exactly the stock limit) |
| Graceful sold-out rejections | 4990 |
| Oversells | **0** |
| Successful-checkout latency | p50 280 ms (84 – 422 ms) |
| All-requests latency | **p50 ~15.5 s** (max ~25.6 s) |
| Total test duration | 30.9 s |

Correctness was perfect, but the 280 ms is only the 10 winners — the 4990 rejected requests queued a **p50 of ~15.5 s** on the per-product lock. That contention ceiling is what motivated the async redesign.

### Async (`jmeter/checkout-async-latency-test.jmx` → `results-async.jtl`)

`POST /checkout` does no DB work — it validates the cart, appends to Kafka, and returns 202.

| Metric | Result |
| :--- | :--- |
| Checkout responses | **5000 × 202**, 0 errors |
| 202 acknowledgment latency | **p50 4 ms**, p95 27 ms, p99 61 ms, max 104 ms |
| Winning orders persisted (end-to-end) | **385 – 850 ms** (all 10 within <1 s of the burst) |
| Full 5000-event backlog drained | ~10.6 s (single consumer, ≈470 jobs/s) |
| Oversells | **0** (stock → 0, exactly 10 orders) |

Median checkout acknowledgment dropped from ~15.5 s to **4 ms** while preserving zero oversells; the order is actually placed end-to-end in under a second, and the full backlog clears in ~10.6 s — a single-consumer throughput characteristic that scales out with partitions/concurrency.

---

## Observability

The signals above — request rate, p99 latency, JVM heap, and Kafka consumer lag — are observable live on a Grafana dashboard rather than only via one-off JMeter runs.


The app exposes `/actuator/prometheus` through Micrometer, with histogram buckets enabled so p99 is computed from real bucket data instead of a client-side estimate. Kafka consumer lag comes free from the Micrometer Kafka binding. Prometheus scrapes it every 15s, keeps 7 days of TSDB on the EBS data volume, and also scrapes itself plus a `node-exporter` for host CPU, memory, and — the one that actually matters here — free space on `/data`, which backs the Prometheus TSDB, MySQL, and Mongo alike.

**Dashboard** (`infra/monitoring/dashboards/shophub.json`, auto-provisioned), grouped into rows:

| Row | Panels |
| :--- | :--- |
| HTTP | request rate, p95/p99 latency, 4xx/5xx per second, 5xx error ratio |
| JVM | heap used vs max, non-heap, GC pause avg/max, GC collections/s, threads live/peak |
| Runtime | process vs system CPU, load average, disk free (as the JVM sees it) |
| Data layer | HikariCP pool state and acquire time, Kafka consumer lag |
| Host | `/data` free %, host CPU, host memory, per-job scrape health |

The HTTP queries exclude `uri="/actuator/prometheus"` — otherwise Prometheus scraping itself every 15s inflates the request rate and drags the latency quantiles toward zero. The host row comes from node-exporter reading `/proc` on the box, which is a different thing from the JVM's own view in the Runtime row: `disk_free_bytes` there is the app container's filesystem, while `/data` free % is the shared EBS volume that would take MySQL, Mongo, and Prometheus down together.

Three alert rules ship with it — app scrape down (2m), p99 over 1s (5m), `/data` under 20% free (10m). They surface in the Grafana alerting UI; no notification channel is wired.

**Config ships as images.** Rather than delivering config files to the box, `prometheus.yml`, the dashboard, the datasource, and the alert rules are baked into `shophub-prometheus` / `shophub-grafana` images that CI rebuilds and publishes on any change under `infra/monitoring/` — the same pattern the nginx image uses for `nginx.conf`. A config change is a normal merge, not a server edit.

**Nothing is publicly exposed.** The app's public `:8080` was removed, so `/actuator` is not internet-reachable at all (port 80 falls through to the SPA). Prometheus, Grafana, and node-exporter bind to `127.0.0.1` on the instance and the security group only opens 22/443/80. Access goes through AWS SSM port forwarding — no inbound port, no SSH key, IAM-authenticated and CloudTrail-logged:

```powershell
.\scripts\grafana-tunnel.ps1              # Grafana  -> http://localhost:3000
.\scripts\grafana-tunnel.ps1 -Prometheus  # Prometheus -> http://localhost:9090
```

The script resolves the instance by tag on every run, because a `terraform apply` that changes `user_data` replaces the box and the ID changes.

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
- Kafka async event pipeline with idempotent consumers, retry topics, and DLTs
- Scheduled order expiry with batch processing
- MongoDB audit trail

---

## Running Locally

**Prerequisites:** Java 21, Node 18+, Docker

**1. Start infrastructure**

```bash
docker compose up -d
```

Starts MySQL, Redis, MongoDB, and Kafka (KRaft mode — no Zookeeper). On first run, Flyway creates the schema from versioned migrations (`db/migration`); Hibernate runs with `ddl-auto: validate` and only verifies that entities match.

**2. Run the app**

```bash
# Backend
./mvnw spring-boot:run

# Frontend (separate terminal)
cd frontend
npm install
npm run dev
```

**3. Configure secrets** via environment variables (`application.yml` ships with dev-only defaults and no real credentials):

```bash
JWT_SECRET=your-256-bit-secret           # optional locally — a dev default is built in
CLOUDINARY_CLOUD_NAME=your-cloud-name    # required only for image upload
CLOUDINARY_API_KEY=your-api-key
CLOUDINARY_API_SECRET=your-api-secret
```

**4. Seed an admin user** — register via the API, then:

```sql
UPDATE users SET role = 'ADMIN' WHERE username = 'your-username';
```

---

## API Overview

| Method | Path | Auth | Description |
| :--- | :--- | :--- | :--- |
| POST | `/api/auth/register` | Public | Register (creates user + cart in one tx) |
| POST | `/api/auth/login` | Public | Login — returns access + refresh token |
| POST | `/api/auth/refresh` | Public | Rotate token pair |
| POST | `/api/auth/logout` | User | Revoke refresh token |
| GET | `/api/categories` | Public | List categories |
| POST | `/api/categories` | Admin | Create category |
| PUT | `/api/categories/{id}` | Admin | Update category |
| GET | `/api/products` | Public | List products (paginated; `?category=`, `?search=`) |
| GET | `/api/products/{id}` | Public | Product detail + live stock (cache-aside) |
| POST | `/api/products` | Admin | Create product + inventory (one tx) |
| PUT | `/api/products/{id}` | Admin | Update product; `status=INACTIVE` soft-deletes |
| PATCH | `/api/products/{id}/inventory` | Admin | Adjust stock by delta |
| GET | `/api/cart` | User | Get cart with live stock |
| POST | `/api/cart/items` | User | Add / increment item |
| PUT | `/api/cart/items/{id}` | User | Set item quantity |
| DELETE | `/api/cart/items/{id}` | User | Remove item |
| POST | `/api/orders/checkout` | User | Initiate async checkout — 202 + `checkoutId` |
| GET | `/api/orders/checkout-status/{checkoutId}` | User | Poll async checkout result |
| GET | `/api/orders/me` | User | My orders (paginated) |
| GET | `/api/orders/{id}` | User/Admin | Order detail |
| POST | `/api/orders/{id}/pay` | User/Admin | Mock payment |
| GET | `/api/orders` | Admin | All orders (paginated) |
| POST | `/api/upload` | Admin | Upload image to Cloudinary |

---

## Project Structure

```
src/main/java/com/example/shophub/
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
