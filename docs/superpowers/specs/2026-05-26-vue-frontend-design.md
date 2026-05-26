# Step 12 — Vue 3 Frontend Design

**Date:** 2026-05-26  
**Status:** Approved

---

## Stack

| Tech | Version | Role |
|---|---|---|
| Vue 3 | latest | UI framework, Composition API |
| Vite | latest | Build tool + dev server |
| Vue Router | 4.x | SPA routing + navigation guards |
| Pinia | 2.x | State management |
| Tailwind CSS | 3.x | Utility-first styling (Apple-minimal aesthetic) |
| Axios | 1.x | HTTP client |

---

## Project Location

`flash-sale-system/frontend/` — standalone Vite project.

Vite dev server runs on `:5173`. All `/api` requests are proxied to `http://localhost:8080` via `vite.config.js`. No CORS changes needed in development.

Production: `npm run build` outputs to `frontend/dist/`. Spring Boot can serve these as static files by copying `dist/` into `src/main/resources/static/` (or via a Maven frontend plugin). Out of scope for this step — dev workflow is sufficient.

---

## Architecture

```
frontend/
├── index.html
├── vite.config.js           ← proxy /api → :8080
├── tailwind.config.js
├── package.json
└── src/
    ├── main.js              ← app + router + pinia setup
    ├── App.vue              ← root layout: NavBar + <RouterView>
    ├── assets/
    │   └── style.css        ← Tailwind directives
    ├── router/
    │   └── index.js         ← routes + navigation guards
    ├── stores/
    │   ├── auth.js          ← Pinia: tokens, role, login/logout/refresh
    │   └── cart.js          ← Pinia: cart item count (nav badge)
    ├── services/
    │   └── api.js           ← axios instance: injects Bearer token, 401 → auto-refresh
    ├── components/
    │   └── NavBar.vue       ← top nav: logo, links, cart badge, role-gated Admin link
    └── views/
        ├── HomeView.vue         ← product grid, search, category filter, add to cart
        ├── LoginView.vue        ← login form, redirects to /
        ├── RegisterView.vue     ← register form, redirects to /
        ├── CartView.vue         ← cart line items, checkout button, stock warnings
        ├── OrdersView.vue       ← my orders list, pay button per PENDING order
        └── admin/
            ├── ProductsView.vue     ← create product form + product list with edit
            ├── InventoryView.vue    ← select product, enter delta + reason, submit PATCH
            └── OrdersView.vue       ← all orders table (paginated)
```

---

## Routing

| Route | Component | Guard |
|---|---|---|
| `/` | `HomeView` | public |
| `/login` | `LoginView` | redirect `/` if already logged in |
| `/register` | `RegisterView` | redirect `/` if already logged in |
| `/cart` | `CartView` | requires auth |
| `/orders` | `OrdersView` | requires auth |
| `/admin/products` | `admin/ProductsView` | requires ADMIN role |
| `/admin/inventory` | `admin/InventoryView` | requires ADMIN role |
| `/admin/orders` | `admin/OrdersView` | requires ADMIN role |

Navigation guards in `router/index.js`:
- No token → redirect `/login` (for auth-required routes)
- Non-ADMIN on `/admin/*` → redirect `/`
- Already logged in on `/login` or `/register` → redirect `/`

---

## State — Pinia Stores

### `auth.js`
```
state: { accessToken, refreshToken, role }
actions:
  login(username, password)     → POST /api/auth/login, persist tokens
  register(username, password)  → POST /api/auth/register, persist tokens
  logout()                      → POST /api/auth/logout, clear state
  refresh()                     → POST /api/auth/refresh, rotate tokens
```
Tokens persisted to `localStorage` so page reload keeps session.

### `cart.js`
```
state: { count }   ← integer, shown as badge on nav Cart link
actions:
  fetchCount()     → GET /api/cart, set count = items.length
  increment()
  decrement()
  reset()
```

---

## Services — `api.js`

Single axios instance:
- `baseURL: '/api'`
- Request interceptor: attach `Authorization: Bearer <accessToken>` from auth store
- Response interceptor: on 401 → call `auth.refresh()` → retry original request once → if retry also 401 → `auth.logout()` + redirect `/login`

All views and stores import this instance, never `fetch` directly.

---

## UI Style

Tailwind utilities only. No component library. Apple-minimal:
- Background: `bg-white`
- Font: `font-[system-ui]` (Apple San Francisco equivalent on macOS/iOS)
- Primary accent: `blue-600` (`#2563eb` — close to Apple's blue)
- Buttons: rounded-lg, solid blue primary / gray secondary
- Cards: `rounded-2xl shadow-sm border border-gray-100`
- Max content width: `max-w-5xl mx-auto px-4`
- Generous whitespace, minimal borders, no gradients

---

## Key Interactions

**Add to cart (HomeView):** POST `/api/cart/items` → increment cart store count → show inline toast if `STOCK_INSUFFICIENT` warning returned.

**Checkout (CartView):** POST `/api/orders/checkout` → on 200 reset cart count, redirect `/orders` → on 409 (sold out) show inline error with product name.

**Pay (OrdersView):** POST `/api/orders/{id}/pay` → refresh order list → on 409 show "already expired/paid".

**Admin create product:** POST `/api/products` with `initialStock` → refresh list.

**Admin inventory adjust:** PATCH `/api/products/{id}/inventory` with `{ delta, reason }`.

---

## Out of Scope

- Production build integration with Spring Boot (Maven frontend plugin)
- Unit tests for Vue components
- Image upload (imageUrl is a text field)
- Pagination UI beyond page 0 (basic first-page display is sufficient for demo)
