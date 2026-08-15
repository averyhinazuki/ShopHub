# 02 — Servlet filters, JWT, and how the app knows who you are

Unit 2. Covers: the filter chain as a pipeline, what a JWT actually is, and where identity
lives once it's established.

---

# Concept 1 — The servlet filter chain ✅

## What problem it solves

Some work must happen on **every** request regardless of endpoint: logging, compression, auth,
CORS, timing. You don't want it copy-pasted into 40 controllers, and controllers shouldn't
know about it.

## How it works

A **servlet** handles a request and produces a response — in Spring Boot essentially just
`DispatcherServlet`, which routes to controllers. A **filter** wraps it:

```java
void doFilter(request, response, FilterChain chain) {
    // runs on the way IN
    chain.doFilter(request, response);   // hand to the next filter (or the servlet)
    // runs on the way OUT
}
```

That one line splits the method. It's an **onion**, not a queue:

```
request ──> [ Filter A ──> [ Filter B ──> [ DispatcherServlet ──> Controller ] ──> ] ──> ]
                  │              │                                          │
            (inbound)      (inbound)                                        │
                  │              └─────────── (outbound) ───────────────────┘
                  └──────────────────── (outbound) ──────────────────────────┘
```

**Two consequences:**

1. **Calling `chain.doFilter()` is what lets the request continue.** A filter blocks a request
   only by *not* calling it. Nothing downstream runs then.
2. **Order determines visibility** — a filter sees only what earlier filters established.
   But see the gotcha below: that applies to the *inbound* halves.

**Spring Security is not a filter — it's a filter chain inside one filter.** The servlet
container sees a single entry (`DelegatingFilterProxy` → `FilterChainProxy`); inside, Spring
Security runs its own ordered list of ~15 filters. `SecurityConfig` configures *that inner
list*, which is why filters are registered *relative to other filters*, not by index.

## Where it is in ShopHub

`SecurityConfig:58-59`:

```java
.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
.addFilterAfter(new UserActionLogFilter(userActionLogService), JwtFilter.class);
```

Relevant order:

```
... → JwtFilter → UserActionLogFilter → ... → AuthorizationFilter → DispatcherServlet → Controller
```

`JwtFilter:19` extends **`OncePerRequestFilter`** — guarantees the body runs exactly once per
request even across internal forwards/includes (an error-page forward would otherwise re-run
it). Gives you `doFilterInternal` instead of `doFilter`.

`SecurityConfig:22` `@EnableMethodSecurity` enables a **second, later** authorization layer
(`@PreAuthorize`, used on `OrderService.getAllOrders`). URL rules and method rules are
separate mechanisms.

## Authentication vs authorization — the key distinction

- **Authentication** — *who are you?* Establish identity, or don't.
- **Authorization** — *are you allowed?* Enforce a rule.

`JwtFilter` does **only** the first. It **contributes information**; it never decides, so it
has nothing to reject. That's why `:49` calls `filterChain.doFilter(...)` **unconditionally**,
outside the `if` — a tokenless request passes down the chain with no authentication set.

**And it must be permissive or the app couldn't work.** `SecurityConfig:45-55` marks many
endpoints `permitAll()`. If `JwtFilter` rejected tokenless requests, **login itself would be
impossible** — you can't present a token before logging in to get one.

## Who returns the 401

Three components, using the outbound pass:

1. **`AuthorizationFilter`** (late, just before the servlet) matches the request against
   `authorizeHttpRequests`. `GET /api/orders/42` falls through every `permitAll()` to
   `.anyRequest().authenticated()` (`:56`), finds `SecurityContextHolder` empty, and **throws**.
2. **`ExceptionTranslationFilter`** (earlier) had wrapped its `chain.doFilter(...)` in
   `try/catch`. The exception propagates back up the onion; it catches it.
3. Anonymous user → "you never identified yourself" → invoke the **`AuthenticationEntryPoint`**.

`SecurityConfig:43` wires a custom one, defined `:30-36`:

```java
response.setStatus(401);
response.setContentType(MediaType.APPLICATION_JSON_VALUE);
response.getWriter().write("{\"error\":\"Unauthorized\"}");
```

**That's the 401.** The payoff: a real status code plus JSON is what lets `api.js`'s response
interceptor detect `err.response?.status === 401` and fire the refresh-and-retry flow.

**401 vs 403** falls out of step 3: anonymous → **401** (entry point, "identify yourself");
authenticated but wrong role → **403** (`AccessDeniedHandler`, "I know you, you still can't").

## Gotchas

- **Only ONE filter enforces authorization** (`AuthorizationFilter`). Everything else
  contributes data. Don't assume a filter's position protects anything.
- **"Later in the chain" ≠ "later in time."** `UserActionLogFilter:37` calls
  `filterChain.doFilter(...)` as its **first** statement and reads the security context at
  `:39` — all its work is on the **outbound** pass. So it would work identically if placed
  *before* `JwtFilter`, because `JwtFilter` runs downstream either way. Position orders the
  inbound halves; the outbound halves run in **reverse**. (→ F5, the class comment's stated
  rationale is wrong.) The real constraint is that it sits where the `SecurityContext` still
  exists, i.e. inside `SecurityContextHolderFilter`, which clears it in a `finally` on the way out.

---

# Concept 2 — What a JWT actually is ✅

## What problem it solves

HTTP is stateless (→ `01`). So on request #2, how does the server know who you are?

**The classical answer is a session:** server stores `{sessionId → user}` and hands you an
**opaque** random ID in a cookie. It's a database key; it means nothing by itself.

That fails at scale: **the server must remember.** With three instances behind a load
balancer, instance B can't resolve a session made by instance A. Options are sticky sessions
(fragile) or a shared Redis session store (a lookup on *every* request, plus a new failure mode).

**JWT inverts it.** Put the facts *in the token* and sign them. The server stores nothing —
it verifies a signature and trusts the contents. Any instance validates any token with no
shared state and no lookup.

## How it works

Three **base64url** segments joined by dots:

```
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJhdmVyeSIsInJvbGUiOiJVU0VSIn0 . 4Zx8kQ2p...
   └── header ──┘        └────────── payload (claims) ─────────┘   └ signature ┘
```

- **Header** — algorithm, e.g. `{"alg":"HS256"}`
- **Payload** — the **claims**, plain JSON. Standard names: `sub` (who), `iat` (issued at),
  `exp` (expires), `jti` (unique token id), plus custom claims.
- **Signature** — `HMAC-SHA256(base64(header) + "." + base64(payload), secret)`

**base64 is NOT encryption.** It's reversible by anyone with no key. Paste any JWT into
jwt.io and read it.

| Property | Provided? |
|---|---|
| **Integrity** — can't be modified | ✅ the signature |
| **Authenticity** — came from the issuer | ✅ the signature |
| **Confidentiality** — can't be read | ❌ **no, not at all** |

Flip `"role":"USER"` → `"role":"ADMIN"` and the signature no longer matches, because
recomputing it needs the secret. Tampering is caught; **reading is free**. Never put anything
secret in a JWT.

**HMAC vs asymmetric:** HMAC (used here) is **symmetric** — one secret both signs and
verifies, so anyone who can verify can also forge. Fine for one app. RSA/ECDSA lets a private
key sign and a public key verify, so other services can validate without gaining forge power.

**The fundamental tradeoff: a stateless token cannot be revoked.** No server-side record
exists to delete. Once issued it's valid until `exp` — logout, bans, password changes cannot
invalidate it.

## Where it is in ShopHub

`JwtUtil:23` — `Keys.hmacShaKeyFor(secret.getBytes())`, symmetric HMAC-SHA256. Secret from
`application.yml:62`, `${JWT_SECRET:local-dev-only-...}`. HMAC-SHA256 requires ≥256 bits, so a
too-short secret throws at **startup** rather than silently weakening — correct fail-fast.

**Two tokens, deliberately asymmetric — this is how the revocation problem gets solved:**

| | Access token (`JwtUtil:29-37`) | Refresh token (`:43-51`) |
|---|---|---|
| Lifetime | **5 min** (`application.yml:63`) | **1 day** (`:64`) |
| `sub` | username | username |
| `role` claim | ✅ | ❌ |
| `jti` | ❌ | ✅ `UUID.randomUUID()` |
| Tracked server-side | no | **yes** — Redis `refresh:{jti}` → userId |
| Revocable | **no** | **yes** |

**Stateless on the hot path** (every API call, zero lookups), **stateful on the cold path**
(once per 5 min, one Redis GET). The access token is unrevocable, so it gets a tiny lifetime
to bound the blast radius. The refresh token lives a day, so it gets an identity and a record.

**Why the access token deliberately has no `jti`:** a `jti` is only useful if you look it up,
and a lookup per request is exactly the cost statelessness bought you. Adding one would
rebuild a session with extra cryptography for nothing.

`RefreshTokenService` — `refresh:{jti}` → userId, TTL mirrors token expiry so Redis
self-cleans. `/logout` → `revoke(jti)`. `/refresh` → `revoke(old)` then `store(new)` =
**rotation**: a stolen refresh token dies the moment the real user refreshes, and if the
attacker refreshes first, the real user's next attempt fails — turning theft into a
**detectable** event.

`JwtUtil:65-72` — `isTokenValid` just parses and catches `JwtException`. `parseSignedClaims`
checks **signature and expiry together**, so one call covers forgery and staleness.

`JwtFilter:32` — `substring(7)` strips `"Bearer "`. `:42` — `"ROLE_" + role`, because
`hasRole('ADMIN')` looks for an authority literally named `ROLE_ADMIN`.

## Gotchas

- **The `role` claim is a login-time snapshot, not live data.** Demote an admin in MySQL and
  their existing access token still claims `ADMIN` until it expires. Nothing is looked up.
- **Revoking a refresh token doesn't stop API calls for up to 5 minutes** — the access token
  is still cryptographically valid. That 5-minute number *is* the exposure window.
- **A per-token denylist isn't implementable here today** — the access token has no `jti`.
  You'd add one (reintroducing a per-request lookup), or denylist by subject:
  `banned:{username}` with a 5-min TTL, which self-cleans since every earlier token has
  expired by then. Coarser but far cheaper.
- **`JwtUtil:28`'s javadoc says "15m"; the config says 5 minutes** (`application.yml:63`).
  Stale comment — trust the config. (→ F6)

---

# Concept 3 — Where identity lives: `SecurityContextHolder` ✅

## What problem it solves

`JwtFilter` establishes identity early in the chain, but the code that needs it is
`OrderService.getOrder`, several layers down. Passing it as a parameter means **every method
signature in the call chain grows a `userId` argument** it may not use, purely to relay it.

## The mechanism: thread-local storage

A **`ThreadLocal`** is a variable whose value is *per-thread*. One static field, each thread
sees its own value — effectively a JVM-managed `Map<Thread, Value>`:

```java
static ThreadLocal<String> holder = new ThreadLocal<>();
// Thread A: holder.set("avery"); holder.get() → "avery"
// Thread B: holder.set("bob");   holder.get() → "bob"
```

**Why it fits HTTP:** in the classic servlet model **one request = one thread**. Tomcat takes a
thread from the pool and runs the *entire* filter chain, controller, and every service call on
it, then returns it. So anything a filter stashes is visible everywhere downstream — no plumbing.

`SecurityContextHolder` is a static facade over a `ThreadLocal<SecurityContext>`.

**Don't confuse the two "contexts":**

| | `ApplicationContext` | `SecurityContext` |
|---|---|---|
| What | The bean container | The current user's `Authentication` |
| Scope | **One per app**, shared by all threads | **One per thread** |
| Crosses threads? | Yes | **No** |

The Kafka listener thread has full access to every Spring bean — it just has no
`SecurityContext`.

## Two critical consequences

**(1) It must be cleared, or identities leak between users.** Threads are pooled and reused. If
request A leaves `avery` in the thread-local and request B gets that thread, B begins
authenticated as avery. Spring prevents it in `SecurityContextHolderFilter`, clearing in a
`finally`. Why this bug class is the worst: it can't reproduce in a one-request-at-a-time test,
it's load-dependent, it's non-deterministic, and it **presents as a data bug** — orders on the
wrong user — so you hunt in `OrderService` and find nothing wrong, because nothing is.

**(2) It does not cross thread boundaries.** `@Async`, an `ExecutorService`, a Kafka listener —
the new thread's context is empty, nothing inherited. **This is the direct reason**
`OrderService.processCheckout(Long userId)` (`:129`) takes the id as a parameter; the comment at
`:122-124` says so. → `03-async-checkout.md`.

## Where it is in ShopHub

**Written** — `JwtFilter:38-45`:

```java
UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
        username,                                             // principal
        null,                                                 // credentials — already verified
        List.of(new SimpleGrantedAuthority("ROLE_" + role)));
SecurityContextHolder.getContext().setAuthentication(auth);
```

`credentials = null` deliberately — the signature already proved authenticity, and holding a
credential you don't need is a liability.

**Read** — `SecurityUtils:14-17`, via `getAuthentication().getName()` (the principal = the `sub`
claim = username), then a lookup for the numeric id.

**Consumed** — `OrderService:86`, `:272`, `:302`, `:313` call `resolveUserId()`; `isAdmin()`
(`:325-329`) reads authorities from the same holder. None take a userId parameter; none know a
filter put it there.

## Gotchas

- **`resolveUserId()` queries MySQL every call** — the token has the username, the app needs the
  PK. Fixed by a `uid` claim. (→ F7a)
- **`.orElseThrow()` → 500 for a deleted user holding a live token**; should be 401. (→ F7b)
- **`jti` ≠ user identity.** A `jti` names *the token* (for revocation); it says nothing about
  *which user*. Adding one would not remove the lookup — it would add a Redis one.
- **Principle:** immutable facts are ideal JWT claims; mutable ones carry staleness risk. `uid`
  never changes → safe to cache in a token. `role` can change → hence the snapshot problem.

## Why 404 and not 403 for someone else's order

`OrderService:317` throws `ResourceNotFoundException` (→ 404) when the order isn't yours. A 403
would be semantically truer — but **403 confirms the order exists**. An attacker walking
`/api/orders/1,2,3…` could read real ids off the status codes: 403 = exists, 404 = doesn't. That
is an **enumeration oracle**, leaking the shape and volume of the order table. Returning 404 for
both makes them indistinguishable — a little HTTP precision traded for not answering questions
nobody asked.

## Questions I should be able to answer

- Why can't a session-based approach scale across instances without shared state?
- What are the three JWT segments? Which of integrity / authenticity / confidentiality do you
  get, and which don't you?
- Is putting `role` in the payload a problem? Why not?
- Why does the refresh token have a `jti` and the access token deliberately not?
- A refresh token is revoked right now. How long can the user still call `/api/orders`?
- What does refresh-token *rotation* buy beyond revocation?
- Which filter throws on an unauthenticated request, which catches it, and which writes the 401?
- Why does `JwtFilter` call `doFilter` even when the token is garbage?
- What is a `ThreadLocal`, and why does one request = one thread make it work for auth?
- Difference between `ApplicationContext` and `SecurityContext` — which crosses threads?
- What bug appears if the security context isn't cleared, and why is it so hard to diagnose?
- Why is `SecurityContextHolder` empty on the Kafka consumer thread?
- Why does `resolveUserId()` need a DB query, and what single claim removes it?
- Why does an order you don't own return 404 rather than 403?
