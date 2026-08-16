# 01 — HTTP, nginx, and the container network

Unit 1. Covers: what's actually on the wire, what a reverse proxy is for, and how one
container reaches another by name.

---

# Concept 1 — What an HTTP request actually is ✅

## What problem it solves

Two programs on different machines need to exchange documents. They must agree on how to say
*which* document, *what to do with it*, how to attach extra information, and how to signal
"message over, your turn." HTTP is that agreement.

**HTTP is just text.** Not an API, not an object — an agreed format for characters written
into a TCP socket. `axios`, `RestTemplate`, `@GetMapping` are convenience layers that
assemble or parse that text. When something breaks at a boundary (nginx, a proxy, CORS), the
text is where the truth is.

## How it works

A request has exactly four parts, in order:

```
POST /api/cart/items HTTP/1.1            ← 1. request line: method, path, version
Host: 52.4.114.4                         ← 2. headers
Authorization: Bearer eyJhbGciOiJIUzI1NiJ9...
Content-Type: application/json
Content-Length: 30
                                         ← 3. blank line (mandatory separator)
{"productId": 7, "quantity": 2}          ← 4. body
```

1. **Request line** — three space-separated tokens: method (what to do), path (which
   resource), version.
2. **Headers** — `Name: value` pairs. Metadata *about* the request, not its content. Names
   are case-insensitive; some may repeat.
3. **A blank line.** Structurally load-bearing — the only signal that headers ended and the
   body began. A stray newline in hand-rolled HTTP breaks everything.
4. **Body** — the payload. Empty for GET/DELETE by convention.

A response is the same shape with a status line instead:

```
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 217

{"id":42,"status":"PENDING"}
```

**Methods:** GET (read, no side effects), POST (create/act), PUT (replace), PATCH (partial),
DELETE. These are conventions *with teeth* — caches and proxies may assume GET is safe to
repeat, so a GET with side effects is a real bug, not a style issue.

**Status codes** group by first digit, and the grouping is a statement about blame:

| Range | Meaning | Whose fault |
|---|---|---|
| 2xx | Success | — |
| 3xx | Redirect | — |
| 4xx | You sent something wrong | Client |
| 5xx | I broke handling it | Server |

**Statelessness:** each request/response pair is independent; the server remembers nothing
between them. This is why a token must be re-sent on *every* request. See `02` for the
consequences.

## Where it is in ShopHub

- `frontend/src/services/api.js:4` — `axios.create({ baseURL: '/api' })` assembles the text.
- `frontend/src/services/api.js:6-12` — a request interceptor re-attaches the
  `Authorization` header on **every** request. This exists *because* HTTP is stateless.
- `infra/nginx/nginx.conf:14` — `location /api/` matches on the path from the request line.
- `nginx.conf:19-22` — `proxy_set_header` writes into the headers section before forwarding.
- `CartController:29` — `@PostMapping("/items")` under `@RequestMapping("/api/cart")`, taking
  `@Valid @RequestBody CartItemRequest` (`productId` Long, `quantity` Integer `@Min(1)`).
- `GlobalExceptionHandler` — the exception → status mapping table:
  | Exception | Status |
  |---|---|
  | `ResourceNotFoundException` | 404 |
  | `SoldOutException` | 409 |
  | `IllegalArgumentException` | 400 |
  | `IllegalStateException` | 409 |
  | `MethodArgumentNotValidException` | 400 (with field details) |
  | `RuntimeException` | 500 |
  | `Exception` | 500 |

## Gotchas

- **`Authorization: Bearer <token>`** — `Authorization` is the header *name*; `Bearer` is
  part of the *value*, an auth scheme prefix. `JwtFilter:31` checks
  `authHeader.startsWith("Bearer ")` — **with a trailing space**. Malform it and the filter
  silently doesn't authenticate; you get a 401 that looks like a bad token, not a bad header.
- **`Content-Type: application/json` is mandatory for a JSON body.** Without it Spring can't
  choose a parser and returns `415 Unsupported Media Type` — the controller never runs. Valid
  JSON isn't enough; you must *declare* it's JSON. Most common cause of "works in Postman,
  fails in code."
- **`Host` is mandatory in HTTP/1.1.** Omit it → 400 before anything else runs.
- **JSON syntax:** keys always double-quoted, the colon sits *outside* the quotes, numbers
  unquoted. `{"productId: 7", quantity:2 "}` is wrong — that makes the key the literal string
  `productId: 7`, so `productId` arrives null and `@NotNull` rejects it with a 400.
- **Headers are NOT carried between requests.** Nothing is; HTTP is stateless. Cookies are the
  exception (browsers auto-resend them); `Authorization` is not a cookie.

## Why credentials go in a header, not the body

1. **Not every request has a body.** `GET /api/orders/42` has none — body-based auth couldn't
   authenticate a GET at all. Headers work uniformly across every method.
2. **The body is a one-shot stream, and the filter runs first.** `JwtFilter` executes before
   any controller and before body parsing, calling `request.getHeader(...)` — cheap and
   repeatable. If the token were in the body, the filter would have to consume the input
   stream to find it; once consumed it's gone, so `@RequestBody` would receive nothing. You'd
   need a buffering request wrapper. Headers sidestep it entirely.
3. **Metadata vs. content.** *Who you are* isn't part of *what item to add*.

## Questions I should be able to answer

- What are the four parts of an HTTP request, in order? What does the blank line do?
- Why does a JSON POST need `Content-Type`? What status comes back without it?
- Why is `Authorization` a header rather than a body field — give the input-stream reason.
- What does the 4xx/5xx split assert? Name a place ShopHub gets it wrong. (→ F4)
- Why does `api.js` need a request interceptor at all?

---

# Concept 2 — Reverse proxies ✅

## What problem it solves

"Serve HTTP to the public internet" is a bundle of jobs, and only one is application logic.
The others: terminate TLS, serve static files efficiently, route URLs to backends, absorb
slow clients, be the single thing you must secure. A JVM is a poor tool for most of that — a
thread handling a slow client is expensive, while nginx holds thousands of idle connections
in fixed memory.

A **reverse proxy** accepts requests and forwards them to other servers, returning the
answers as though it produced them. The client never knows.

**Forward vs reverse** — a *forward* proxy sits in front of **clients** (corporate egress,
VPN) and hides *who is asking*; the client opts in. A *reverse* proxy sits in front of
**servers** and hides *what is answering*; the client can't tell it exists. Same mechanic,
opposite side.

## What it buys you, and which ShopHub actually uses

| # | Benefit | Used here? |
|---|---|---|
| 1 | Single public entry point | ✅ only `nginx` publishes `80:80`; `app` publishes nothing |
| 2 | TLS termination | ❌ prepared not built — `security.tf:38-44` opens 443, nginx only `listen 80` |
| 3 | Static file serving | ✅ `nginx.conf:30-31`, caching at `:40-44` |
| 4 | Load balancing | ❌ one `app` container; would become an `upstream` block |
| 5 | Path-based routing | ✅ `/api/` → app, `/` → static |
| 6 | Absorbing slow clients | ✅ partly — `nginx.conf:25-26` timeouts, "headroom for checkout under load" |

## Why static files belong in nginx, not Spring — the cost argument

Spring Boot *can* serve them (`resources/static/`). It shouldn't:

- **Tomcat's thread pool is bounded** (200 default), one thread occupied per in-flight
  request. nginx uses an **event loop** — one worker, thousands of connections, no
  thread-per-connection — and `sendfile()` zero-copy, where bytes go disk → socket without
  entering user space.
- **The consequence for ShopHub specifically:** on Spring, a burst of JS/image requests
  competes for the same 200 threads as checkout. In a flash sale, static assets would starve
  the API. In nginx they can't touch checkout capacity.
- **Secondary:** separate images mean a CSS change redeploys without restarting the JVM —
  which is why CI has independent `publish-frontend` / `publish-backend` jobs.

## Where the Vue files actually live

**Not on the EC2 filesystem at all.** `Dockerfile.frontend` is a two-stage build: stage 1
(`node:22-alpine`) runs `npm run build` → `/app/dist`, then is discarded; stage 2
(`nginx:1.27-alpine`) does `COPY --from=build /app/dist /usr/share/nginx/html` (`:27`) and
bakes the config at `:26`. The assets live **inside the `shophub-frontend` image**, present
only in the running container's filesystem. No bind mounts (`:20-21`) — so the frontend is
one immutable versioned artifact, and a fresh `terraform apply` comes up with the real UI.

## The SPA fallback

```nginx
location / { try_files $uri $uri/ /index.html; }     # nginx.conf:33-37
```

Vue Router uses HTML5 history mode, so `/orders` is a **client-side** route with no file on
disk. `try_files` tries the file, then the directory, then serves `index.html` regardless —
the app boots and reads the URL itself. Without this line, refreshing on `/orders` returns a
404 from nginx. The classic SPA deployment bug.

## Gotchas

- **TLS terminates at nginx.** `proxy_pass http://app:8080` is literally `http://`. Only the
  browser↔nginx leg would be HTTPS; nginx↔app stays plaintext on a private network, and the
  app never holds a certificate. Consequence: the app can't tell the original scheme, which
  is why `nginx.conf:22` already sets `X-Forwarded-Proto $scheme`.
- **Firewall-open ≠ listening, and they fail differently:**

  | Problem | Packet fate | Symptom |
  |---|---|---|
  | Security group blocks port | dropped silently | **timeout**, hangs ~30s |
  | Port allowed, nothing listening | kernel sends RST | **connection refused**, instant |

  Instant refusal = "reached the box, nobody home" → check app/container. Hang = "never
  reached the box" → check security group/routing. Reversing these costs hours.

---

# Concept 3 — Published ports and the container network ✅

## The governing rule

**Publishing a port is only ever for traffic originating outside Docker.** Container-to-
container traffic never needs one — the bridge network and its embedded DNS handle that.
`app` reaches MySQL at `mysql:3306` in *both* compose files because inside the network the
port was always 3306; publishing changes nothing about that.

## How name resolution works

Compose creates a default bridge network for the project (no `networks:` block is declared,
so everything lands on it). Docker runs an **embedded DNS server at 127.0.0.11** that
resolves **service names** to container IPs. So `proxy_pass http://app:8080` works because
`app` is a Compose service name — not a hostname on the internet, and meaningless outside
Docker. `localhost:8080` inside the nginx container would be *nginx itself* → 502.

Also: `proxy_pass http://app:8080` has **no trailing slash**, so the original URI passes
through unchanged. With `http://app:8080/` nginx would strip the matched `/api/` prefix and
every controller would 404.

## The three tiers in `docker-compose.cloud.yml`

| Tier | Services | `ports:` | Reachable by |
|---|---|---|---|
| Invisible | `app`, `mysql`, `redis`, `kafka`, `mongodb` | none | sibling containers only |
| Loopback-only | `node-exporter`, `prometheus`, `grafana` | `127.0.0.1:9100:9100`, `127.0.0.1:9090:9090`, `127.0.0.1:3000:3000` | processes **on the EC2 box** only |
| Public | `nginx` | `"80:80"` | the internet |

The `127.0.0.1:` prefix binds the published port to the host's **loopback interface only**.
A packet from the internet can never reach Grafana — no security group rule needed, the
binding itself is the boundary. **This is why `scripts/grafana-tunnel.ps1` exists:** you SSH
in and forward a local port so your traffic arrives as if from localhost. Not a convenience,
the only way in.

## Why local dev publishes everything

Locally *you* are the outsider — IntelliJ, DBeaver, browser, `curl` all run on Windows, not
on the Docker network, and cannot resolve `mysql` or `app`. So each service you poke needs a
published port: `8080`, `3307:3306`, `27017`, `6379`, `9092`, `9100`, `9090`, `3000`.

Mapping direction is **HOST:CONTAINER**. `"3307:3306"` = host 3307 → container 3306, because
a native MySQL 5.7 already squats on host 3306. The container still listens on 3306 and the
app still uses `mysql:3306`; only desktop tools use 3307.

**The security consequence:** adding `ports: "3306:3306"` to `mysql` in the *cloud* file
would put the database on the public internet with only `security.tf` in front of it. The
absence of `ports:` there is the security model, not an oversight.

## Questions I should be able to answer

- Why does `proxy_pass http://app:8080` resolve, and why would `localhost:8080` fail there?
- What are the three port tiers in the cloud compose file, and what does `127.0.0.1:` change?
- Why does the Grafana tunnel script have to exist?
- Which direction is `"3307:3306"`, and why 3307?
- Why is static file serving nginx's job and not Spring's — in terms of threads?
- After adding TLS, what protocol does nginx speak to `app`? Which header carries the
  original scheme?
- Two boxes: one refuses instantly, one hangs. Which layer is broken in each?
