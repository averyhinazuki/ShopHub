# Vue 3 Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Vue 3 SPA frontend for the flash-sale-system Spring Boot backend, served at `:5173` in dev with Vite proxying `/api` to `:8080`.

**Architecture:** Standalone Vite project in `flash-sale-system/frontend/`. Pinia stores hold auth tokens (persisted to localStorage) and cart count (nav badge). A single axios instance injects the Bearer token on every request and auto-refreshes on 401. Vue Router guards redirect unauthenticated and non-admin users. Apple-minimal UI via Tailwind utilities — white background, system-ui font, blue-600 accent.

**Tech Stack:** Vue 3 (Composition API), Vite 6, Vue Router 4, Pinia 2, Tailwind CSS 3, Axios 1

---

## File Map

| File | Responsibility |
|---|---|
| `frontend/vite.config.js` | Vite plugin + `/api` proxy to `:8080` |
| `frontend/tailwind.config.js` | Tailwind content paths |
| `frontend/src/assets/style.css` | Tailwind directives (only CSS file) |
| `frontend/src/services/api.js` | Axios instance: Bearer injection + 401 auto-refresh |
| `frontend/src/stores/auth.js` | Pinia: tokens, role, login/register/logout/refresh |
| `frontend/src/stores/cart.js` | Pinia: cart item count for nav badge |
| `frontend/src/router/index.js` | Routes + navigation guards (auth, admin, guestOnly) |
| `frontend/src/main.js` | App bootstrap: Pinia → Router → mount |
| `frontend/src/App.vue` | Root layout: NavBar + RouterView |
| `frontend/src/components/NavBar.vue` | Top nav: logo, links, cart badge, role-gated Admin |
| `frontend/src/views/LoginView.vue` | Login form |
| `frontend/src/views/RegisterView.vue` | Register form |
| `frontend/src/views/HomeView.vue` | Product grid, search, category filter, add to cart |
| `frontend/src/views/CartView.vue` | Cart line items, qty controls, checkout |
| `frontend/src/views/OrdersView.vue` | My orders list, pay button |
| `frontend/src/views/admin/ProductsView.vue` | Create product form + product list with status toggle |
| `frontend/src/views/admin/InventoryView.vue` | Delta adjust form (PATCH inventory) |
| `frontend/src/views/admin/OrdersView.vue` | All orders table (admin) |

---

## Task 1: Scaffold Vite Project + Install Dependencies

**Files:**
- Create: `frontend/` (entire directory via Vite scaffold)

- [ ] **Step 1: Scaffold**

From `flash-sale-system/` directory:

```bash
npm create vite@latest frontend -- --template vue
```

- [ ] **Step 2: Install all dependencies**

```bash
cd frontend
npm install
npm install vue-router@4 pinia axios
npm install -D tailwindcss@3 postcss autoprefixer
npx tailwindcss init -p
```

- [ ] **Step 3: Delete default boilerplate files**

```bash
rm src/components/HelloWorld.vue
rm src/assets/vue.svg
rm public/vite.svg
```

- [ ] **Step 4: Verify scaffold runs**

```bash
npm run dev
```

Expected: Vite dev server starts on `http://localhost:5173`. Browser shows default Vue page (will be replaced in subsequent tasks).

- [ ] **Step 5: Commit**

```bash
git add frontend/
git commit -m "feat: scaffold Vue 3 + Vite frontend project"
```

---

## Task 2: Configure Vite Proxy + Tailwind

**Files:**
- Modify: `frontend/vite.config.js`
- Modify: `frontend/tailwind.config.js`
- Create: `frontend/src/assets/style.css`

- [ ] **Step 1: Write `vite.config.js`**

```js
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

- [ ] **Step 2: Write `tailwind.config.js`**

```js
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{vue,js}'],
  theme: { extend: {} },
  plugins: [],
}
```

- [ ] **Step 3: Write `src/assets/style.css`**

```css
@tailwind base;
@tailwind components;
@tailwind utilities;
```

- [ ] **Step 4: Verify Tailwind works**

In `src/App.vue` (temporary check), add `class="bg-blue-100"` to the root div. Run `npm run dev` and confirm the background is light blue. Remove the class afterward.

- [ ] **Step 5: Commit**

```bash
git add frontend/vite.config.js frontend/tailwind.config.js frontend/src/assets/style.css
git commit -m "feat: configure Vite proxy and Tailwind CSS"
```

---

## Task 3: Services — `api.js`

**Files:**
- Create: `frontend/src/services/api.js`

- [ ] **Step 1: Write `src/services/api.js`**

```js
import axios from 'axios'
import { useAuthStore } from '../stores/auth'

const api = axios.create({ baseURL: '/api' })

api.interceptors.request.use(config => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

api.interceptors.response.use(
  res => res,
  async err => {
    const auth = useAuthStore()
    if (err.response?.status === 401 && !err.config._retry) {
      err.config._retry = true
      try {
        await auth.refresh()
        err.config.headers.Authorization = `Bearer ${auth.accessToken}`
        return api(err.config)
      } catch {
        auth.clear()
        window.location.href = '/login'
      }
    }
    return Promise.reject(err)
  }
)

export default api
```

> Uses `window.location.href` instead of `router.push` to avoid circular imports (`api.js` ← `router` ← `stores/auth`).

- [ ] **Step 2: Commit**

```bash
git add frontend/src/services/api.js
git commit -m "feat: add axios api service with Bearer injection and 401 auto-refresh"
```

---

## Task 4: Auth Store

**Files:**
- Create: `frontend/src/stores/auth.js`

- [ ] **Step 1: Write `src/stores/auth.js`**

```js
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem('accessToken') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const role = ref(localStorage.getItem('role') || '')

  const isLoggedIn = computed(() => !!accessToken.value)
  const isAdmin = computed(() => role.value === 'ADMIN')

  function setTokens(at, rt, userRole) {
    accessToken.value = at
    refreshToken.value = rt
    role.value = userRole
    localStorage.setItem('accessToken', at)
    localStorage.setItem('refreshToken', rt)
    localStorage.setItem('role', userRole)
  }

  function clear() {
    accessToken.value = ''
    refreshToken.value = ''
    role.value = ''
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('role')
  }

  function decodeRole(token) {
    // JWT payload is base64url-encoded. Extracts the 'role' claim.
    const payload = JSON.parse(atob(token.split('.')[1]))
    return payload.role || ''
  }

  async function login(username, password) {
    const res = await axios.post('/api/auth/login', { username, password })
    const { accessToken: at, refreshToken: rt } = res.data
    setTokens(at, rt, decodeRole(at))
  }

  async function register(username, password) {
    const res = await axios.post('/api/auth/register', { username, password })
    const { accessToken: at, refreshToken: rt } = res.data
    setTokens(at, rt, decodeRole(at))
  }

  async function logout() {
    try {
      await axios.post('/api/auth/logout',
        { refreshToken: refreshToken.value },
        { headers: { Authorization: `Bearer ${accessToken.value}` } }
      )
    } finally {
      clear()
    }
  }

  async function refresh() {
    const res = await axios.post('/api/auth/refresh', { refreshToken: refreshToken.value })
    const { accessToken: at, refreshToken: rt } = res.data
    setTokens(at, rt, decodeRole(at))
  }

  return { accessToken, refreshToken, role, isLoggedIn, isAdmin, login, register, logout, refresh, clear }
})
```

> Uses raw `axios` (not the `api` service) to avoid circular dependency. The `role` claim in the JWT is the Java enum name: `"USER"` or `"ADMIN"`.

- [ ] **Step 2: Commit**

```bash
git add frontend/src/stores/auth.js
git commit -m "feat: add Pinia auth store with token persistence and JWT decode"
```

---

## Task 5: Cart Store

**Files:**
- Create: `frontend/src/stores/cart.js`

- [ ] **Step 1: Write `src/stores/cart.js`**

```js
import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '../services/api'

export const useCartStore = defineStore('cart', () => {
  const count = ref(0)

  async function fetchCount() {
    try {
      const res = await api.get('/cart')
      count.value = res.data.items?.length ?? 0
    } catch {
      count.value = 0
    }
  }

  function increment() { count.value++ }
  function decrement() { count.value = Math.max(0, count.value - 1) }
  function reset() { count.value = 0 }

  return { count, fetchCount, increment, decrement, reset }
})
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/stores/cart.js
git commit -m "feat: add Pinia cart store for nav badge count"
```

---

## Task 6: Router

**Files:**
- Create: `frontend/src/router/index.js`

- [ ] **Step 1: Write `src/router/index.js`**

```js
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import HomeView from '../views/HomeView.vue'
import LoginView from '../views/LoginView.vue'
import RegisterView from '../views/RegisterView.vue'
import CartView from '../views/CartView.vue'
import OrdersView from '../views/OrdersView.vue'
import AdminProductsView from '../views/admin/ProductsView.vue'
import AdminInventoryView from '../views/admin/InventoryView.vue'
import AdminOrdersView from '../views/admin/OrdersView.vue'

const routes = [
  { path: '/',                    component: HomeView },
  { path: '/login',               component: LoginView,           meta: { guestOnly: true } },
  { path: '/register',            component: RegisterView,        meta: { guestOnly: true } },
  { path: '/cart',                component: CartView,            meta: { requiresAuth: true } },
  { path: '/orders',              component: OrdersView,          meta: { requiresAuth: true } },
  { path: '/admin/products',      component: AdminProductsView,   meta: { requiresAdmin: true } },
  { path: '/admin/inventory',     component: AdminInventoryView,  meta: { requiresAdmin: true } },
  { path: '/admin/orders',        component: AdminOrdersView,     meta: { requiresAdmin: true } },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to, _from, next) => {
  const auth = useAuthStore()
  if (to.meta.guestOnly   && auth.isLoggedIn) return next('/')
  if (to.meta.requiresAuth && !auth.isLoggedIn) return next('/login')
  if (to.meta.requiresAdmin && !auth.isAdmin)   return next('/')
  next()
})

export default router
```

- [ ] **Step 2: Create stub view files so the router import does not crash**

Create empty stub content for each view (will be replaced in later tasks):

`src/views/HomeView.vue`:
```vue
<template><div>Home</div></template>
```

`src/views/LoginView.vue`:
```vue
<template><div>Login</div></template>
```

`src/views/RegisterView.vue`:
```vue
<template><div>Register</div></template>
```

`src/views/CartView.vue`:
```vue
<template><div>Cart</div></template>
```

`src/views/OrdersView.vue`:
```vue
<template><div>Orders</div></template>
```

`src/views/admin/ProductsView.vue`:
```vue
<template><div>Admin Products</div></template>
```

`src/views/admin/InventoryView.vue`:
```vue
<template><div>Admin Inventory</div></template>
```

`src/views/admin/OrdersView.vue`:
```vue
<template><div>Admin Orders</div></template>
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/router/ frontend/src/views/
git commit -m "feat: add Vue Router with auth/admin navigation guards"
```

---

## Task 7: App Shell — `main.js` + `App.vue`

**Files:**
- Modify: `frontend/src/main.js`
- Modify: `frontend/src/App.vue`

- [ ] **Step 1: Write `src/main.js`**

```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import './assets/style.css'
import App from './App.vue'

const app = createApp(App)
app.use(createPinia()) // Pinia MUST be registered before router (guards use auth store)
app.use(router)
app.mount('#app')
```

- [ ] **Step 2: Write `src/App.vue`**

```vue
<template>
  <div class="min-h-screen bg-white text-gray-900" style="font-family: system-ui, -apple-system, sans-serif">
    <NavBar />
    <main class="max-w-5xl mx-auto px-4 py-10">
      <RouterView />
    </main>
  </div>
</template>

<script setup>
import NavBar from './components/NavBar.vue'
</script>
```

- [ ] **Step 3: Verify routing works**

```bash
npm run dev
```

Navigate to `http://localhost:5173`. Should render "Home" text. Navigate to `/login` — renders "Login". Navigate to `/cart` — redirects to `/login` (guard working).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/main.js frontend/src/App.vue
git commit -m "feat: bootstrap Vue app with Pinia and router"
```

---

## Task 8: NavBar Component

**Files:**
- Create: `frontend/src/components/NavBar.vue`

- [ ] **Step 1: Write `src/components/NavBar.vue`**

```vue
<template>
  <nav class="border-b border-gray-100 bg-white/80 backdrop-blur sticky top-0 z-50">
    <div class="max-w-5xl mx-auto px-4 h-14 flex items-center justify-between">
      <RouterLink to="/" class="font-semibold text-gray-900 tracking-tight text-sm">
        FlashSale
      </RouterLink>

      <div class="flex items-center gap-6 text-sm text-gray-500">
        <RouterLink to="/" class="hover:text-gray-900 transition-colors">Products</RouterLink>

        <template v-if="auth.isLoggedIn">
          <RouterLink to="/cart" class="relative hover:text-gray-900 transition-colors">
            Cart
            <span v-if="cart.count > 0"
              class="absolute -top-2 -right-3.5 bg-blue-600 text-white text-[9px] font-semibold rounded-full w-4 h-4 flex items-center justify-center">
              {{ cart.count > 9 ? '9+' : cart.count }}
            </span>
          </RouterLink>

          <RouterLink to="/orders" class="hover:text-gray-900 transition-colors">Orders</RouterLink>

          <template v-if="auth.isAdmin">
            <span class="text-gray-200">|</span>
            <RouterLink to="/admin/products" class="hover:text-gray-900 transition-colors">Products</RouterLink>
            <RouterLink to="/admin/inventory" class="hover:text-gray-900 transition-colors">Inventory</RouterLink>
            <RouterLink to="/admin/orders" class="hover:text-gray-900 transition-colors">All Orders</RouterLink>
          </template>

          <button @click="handleLogout" class="hover:text-gray-900 transition-colors">
            Sign out
          </button>
        </template>

        <template v-else>
          <RouterLink to="/login" class="hover:text-gray-900 transition-colors">Sign in</RouterLink>
          <RouterLink to="/register"
            class="bg-blue-600 text-white px-3.5 py-1.5 rounded-lg hover:bg-blue-700 transition-colors text-sm font-medium">
            Register
          </RouterLink>
        </template>
      </div>
    </div>
  </nav>
</template>

<script setup>
import { onMounted } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useCartStore } from '../stores/cart'
import { useRouter } from 'vue-router'

const auth = useAuthStore()
const cart = useCartStore()
const router = useRouter()

// Restore cart count on page reload (localStorage keeps auth but count is in-memory)
onMounted(() => {
  if (auth.isLoggedIn) cart.fetchCount()
})

async function handleLogout() {
  await auth.logout()
  cart.reset()
  router.push('/login')
}
</script>
```

- [ ] **Step 2: Verify nav renders**

Run `npm run dev`. Nav bar should appear at top — "FlashSale" logo left, "Products" + "Sign in" + "Register" right (when not logged in).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/components/NavBar.vue
git commit -m "feat: add NavBar with auth-aware links and cart badge"
```

---

## Task 9: Auth Views — Login + Register

**Files:**
- Modify: `frontend/src/views/LoginView.vue`
- Modify: `frontend/src/views/RegisterView.vue`

- [ ] **Step 1: Write `src/views/LoginView.vue`**

```vue
<template>
  <div class="max-w-sm mx-auto mt-24">
    <h1 class="text-2xl font-semibold text-gray-900 mb-8 tracking-tight">Sign in</h1>

    <form @submit.prevent="handleLogin" class="space-y-4">
      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">Username</label>
        <input v-model="username" type="text" required autocomplete="username"
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 focus:border-transparent transition" />
      </div>
      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">Password</label>
        <input v-model="password" type="password" required autocomplete="current-password"
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 focus:border-transparent transition" />
      </div>

      <p v-if="error" class="text-sm text-red-500">{{ error }}</p>

      <button type="submit" :disabled="loading"
        class="w-full bg-blue-600 text-white rounded-xl py-2.5 text-sm font-medium
               hover:bg-blue-700 disabled:opacity-50 transition mt-2">
        {{ loading ? 'Signing in…' : 'Sign in' }}
      </button>
    </form>

    <p class="mt-6 text-sm text-gray-400 text-center">
      No account?
      <RouterLink to="/register" class="text-blue-600 hover:underline">Register</RouterLink>
    </p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCartStore } from '../stores/cart'

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

async function handleLogin() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value, password.value)
    await cart.fetchCount()
    router.push('/')
  } catch {
    error.value = 'Invalid username or password.'
  } finally {
    loading.value = false
  }
}
</script>
```

- [ ] **Step 2: Write `src/views/RegisterView.vue`**

```vue
<template>
  <div class="max-w-sm mx-auto mt-24">
    <h1 class="text-2xl font-semibold text-gray-900 mb-8 tracking-tight">Create account</h1>

    <form @submit.prevent="handleRegister" class="space-y-4">
      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">Username</label>
        <input v-model="username" type="text" required autocomplete="username"
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 focus:border-transparent transition" />
      </div>
      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">Password</label>
        <input v-model="password" type="password" required autocomplete="new-password"
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 focus:border-transparent transition" />
      </div>

      <p v-if="error" class="text-sm text-red-500">{{ error }}</p>

      <button type="submit" :disabled="loading"
        class="w-full bg-blue-600 text-white rounded-xl py-2.5 text-sm font-medium
               hover:bg-blue-700 disabled:opacity-50 transition mt-2">
        {{ loading ? 'Creating account…' : 'Create account' }}
      </button>
    </form>

    <p class="mt-6 text-sm text-gray-400 text-center">
      Already have an account?
      <RouterLink to="/login" class="text-blue-600 hover:underline">Sign in</RouterLink>
    </p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCartStore } from '../stores/cart'

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

async function handleRegister() {
  error.value = ''
  loading.value = true
  try {
    await auth.register(username.value, password.value)
    await cart.fetchCount()
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.message || 'Registration failed.'
  } finally {
    loading.value = false
  }
}
</script>
```

- [ ] **Step 3: Verify (requires Spring Boot running on :8080)**

Navigate to `/login`. Submit valid credentials → should redirect to `/`. Nav shows "Cart", "Orders", "Sign out". Sign out → redirects to `/login`.

Navigate to `/register`. Submit new username → should redirect to `/`.

- [ ] **Step 4: Commit**

```bash
git add frontend/src/views/LoginView.vue frontend/src/views/RegisterView.vue
git commit -m "feat: add Login and Register views"
```

---

## Task 10: HomeView — Product Grid

**Files:**
- Modify: `frontend/src/views/HomeView.vue`

- [ ] **Step 1: Write `src/views/HomeView.vue`**

```vue
<template>
  <div>
    <!-- Search + category filter -->
    <div class="flex flex-col sm:flex-row gap-3 mb-10">
      <input v-model="search" @input="debouncedFetch" placeholder="Search products…"
        class="flex-1 border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
               focus:ring-2 focus:ring-blue-600 focus:border-transparent transition" />
      <select v-model="selectedCategory" @change="fetchProducts"
        class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
               focus:ring-2 focus:ring-blue-600 focus:border-transparent transition bg-white">
        <option value="">All categories</option>
        <option v-for="cat in categories" :key="cat.id" :value="cat.id">{{ cat.name }}</option>
      </select>
    </div>

    <!-- States -->
    <div v-if="loading" class="text-center text-gray-300 py-24 text-sm">Loading…</div>
    <div v-else-if="products.length === 0" class="text-center text-gray-300 py-24 text-sm">
      No products found.
    </div>

    <!-- Product grid -->
    <div v-else class="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-5">
      <div v-for="p in products" :key="p.id"
        class="rounded-2xl border border-gray-100 shadow-sm overflow-hidden flex flex-col">
        <!-- Image area -->
        <div class="aspect-square bg-gray-50 flex items-center justify-center overflow-hidden">
          <img v-if="p.imageUrl" :src="p.imageUrl" :alt="p.name"
            class="w-full h-full object-cover" />
          <span v-else class="text-gray-200 text-xs">No image</span>
        </div>

        <!-- Info -->
        <div class="p-4 flex flex-col gap-3 flex-1">
          <div>
            <p class="font-medium text-gray-900 text-sm leading-snug">{{ p.name }}</p>
            <p class="text-xs text-gray-400 mt-0.5">{{ p.categoryName }}</p>
          </div>
          <div class="flex items-center justify-between mt-auto">
            <span class="text-sm font-semibold text-gray-900">${{ p.price }}</span>
            <span class="text-xs text-gray-400">{{ p.availableStock }} left</span>
          </div>
          <button @click="addToCart(p)"
            :disabled="p.availableStock === 0 || addingId === p.id"
            class="w-full bg-blue-600 text-white rounded-xl py-2 text-sm font-medium
                   hover:bg-blue-700 disabled:opacity-40 transition">
            {{ p.availableStock === 0 ? 'Sold out' : addingId === p.id ? 'Adding…' : 'Add to cart' }}
          </button>
        </div>
      </div>
    </div>

    <!-- Toast -->
    <div v-if="toast"
      class="fixed bottom-6 right-6 bg-gray-900 text-white text-sm px-4 py-3 rounded-xl shadow-lg transition-opacity">
      {{ toast }}
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../services/api'
import { useAuthStore } from '../stores/auth'
import { useCartStore } from '../stores/cart'

const products = ref([])
const categories = ref([])
const search = ref('')
const selectedCategory = ref('')
const loading = ref(false)
const addingId = ref(null)
const toast = ref('')
const auth = useAuthStore()
const cart = useCartStore()
const router = useRouter()

let debounceTimer = null
function debouncedFetch() {
  clearTimeout(debounceTimer)
  debounceTimer = setTimeout(fetchProducts, 300)
}

async function fetchProducts() {
  loading.value = true
  try {
    const params = { page: 0, size: 20 }
    if (search.value) params.search = search.value
    if (selectedCategory.value) params.category = selectedCategory.value
    const res = await api.get('/products', { params })
    products.value = res.data.content ?? res.data
  } finally {
    loading.value = false
  }
}

async function fetchCategories() {
  const res = await api.get('/categories')
  categories.value = res.data
}

function showToast(msg) {
  toast.value = msg
  setTimeout(() => { toast.value = '' }, 3000)
}

async function addToCart(product) {
  if (!auth.isLoggedIn) { router.push('/login'); return }
  addingId.value = product.id
  try {
    const res = await api.post('/cart/items', { productId: product.id, quantity: 1 })
    cart.increment()
    if (res.data.warning) {
      showToast(`Only ${res.data.warning.available} in stock — item added anyway.`)
    } else {
      showToast('Added to cart.')
    }
  } catch {
    showToast('Could not add to cart.')
  } finally {
    addingId.value = null
  }
}

onMounted(() => {
  fetchCategories()
  fetchProducts()
})
</script>
```

- [ ] **Step 2: Verify**

Navigate to `/`. Products grid should render. Use search box — list filters after 300ms. Use category dropdown — list filters on change. Click "Add to cart" — toast appears, cart badge increments (if logged in) or redirects to login (if not).

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/HomeView.vue
git commit -m "feat: add HomeView product grid with search, filter, and add to cart"
```

---

## Task 11: CartView

**Files:**
- Modify: `frontend/src/views/CartView.vue`

- [ ] **Step 1: Write `src/views/CartView.vue`**

```vue
<template>
  <div class="max-w-2xl mx-auto">
    <h1 class="text-2xl font-semibold text-gray-900 mb-8 tracking-tight">Cart</h1>

    <div v-if="loading" class="text-center text-gray-300 py-24 text-sm">Loading…</div>

    <div v-else-if="items.length === 0" class="text-center text-gray-300 py-24 text-sm">
      Your cart is empty.
      <RouterLink to="/" class="text-blue-600 ml-1 hover:underline">Browse products</RouterLink>
    </div>

    <div v-else>
      <!-- Line items -->
      <div class="space-y-3 mb-8">
        <div v-for="item in items" :key="item.id"
          class="flex items-center gap-4 p-4 rounded-2xl border border-gray-100 shadow-sm">
          <div class="flex-1 min-w-0">
            <p class="text-sm font-medium text-gray-900 truncate">{{ item.productName }}</p>
            <p class="text-xs text-gray-400 mt-0.5">${{ item.price }} · {{ item.availableStock }} in stock</p>
          </div>

          <!-- Qty controls -->
          <div class="flex items-center gap-2 shrink-0">
            <button @click="updateQty(item, item.quantity - 1)"
              class="w-7 h-7 rounded-lg border border-gray-200 text-gray-500 hover:border-gray-400 transition text-base leading-none">
              −
            </button>
            <span class="w-6 text-center text-sm font-medium">{{ item.quantity }}</span>
            <button @click="updateQty(item, item.quantity + 1)"
              class="w-7 h-7 rounded-lg border border-gray-200 text-gray-500 hover:border-gray-400 transition text-base leading-none">
              +
            </button>
          </div>

          <p class="text-sm font-semibold w-16 text-right shrink-0">
            ${{ (item.price * item.quantity).toFixed(2) }}
          </p>

          <button @click="removeItem(item)"
            class="text-gray-300 hover:text-red-400 transition text-xl leading-none ml-1 shrink-0">
            ×
          </button>
        </div>
      </div>

      <!-- Total + checkout -->
      <div class="flex items-center justify-between pt-5 border-t border-gray-100">
        <div>
          <p class="text-xs text-gray-400 mb-0.5">Total</p>
          <p class="text-xl font-semibold text-gray-900">${{ total }}</p>
        </div>
        <button @click="checkout" :disabled="checkingOut"
          class="bg-blue-600 text-white px-7 py-2.5 rounded-xl text-sm font-medium
                 hover:bg-blue-700 disabled:opacity-50 transition">
          {{ checkingOut ? 'Processing…' : 'Checkout' }}
        </button>
      </div>

      <p v-if="checkoutError" class="mt-4 text-sm text-red-500">{{ checkoutError }}</p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '../services/api'
import { useCartStore } from '../stores/cart'

const items = ref([])
const loading = ref(false)
const checkingOut = ref(false)
const checkoutError = ref('')
const router = useRouter()
const cart = useCartStore()

const total = computed(() =>
  items.value.reduce((sum, i) => sum + i.price * i.quantity, 0).toFixed(2)
)

async function fetchCart() {
  loading.value = true
  try {
    const res = await api.get('/cart')
    items.value = res.data.items ?? []
  } finally {
    loading.value = false
  }
}

async function updateQty(item, newQty) {
  if (newQty < 1) { removeItem(item); return }
  // CartItemRequest requires productId even on PUT (same DTO as POST)
  await api.put(`/cart/items/${item.id}`, { productId: item.productId, quantity: newQty })
  item.quantity = newQty
}

async function removeItem(item) {
  await api.delete(`/cart/items/${item.id}`)
  items.value = items.value.filter(i => i.id !== item.id)
  cart.decrement()
}

async function checkout() {
  checkoutError.value = ''
  checkingOut.value = true
  try {
    await api.post('/orders/checkout')
    cart.reset()
    router.push('/orders')
  } catch (e) {
    checkoutError.value = e.response?.data?.message || 'Checkout failed. Some items may be sold out.'
  } finally {
    checkingOut.value = false
  }
}

onMounted(fetchCart)
</script>
```

- [ ] **Step 2: Verify**

Log in as a user. Add items to cart from HomeView. Navigate to `/cart`. Line items appear with qty controls. Click `−` to decrement (remove if qty reaches 0). Click `×` to remove. Click Checkout → redirects to `/orders` on success, shows error on sold-out.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/CartView.vue
git commit -m "feat: add CartView with qty controls and checkout"
```

---

## Task 12: OrdersView

**Files:**
- Modify: `frontend/src/views/OrdersView.vue`

- [ ] **Step 1: Write `src/views/OrdersView.vue`**

```vue
<template>
  <div class="max-w-2xl mx-auto">
    <h1 class="text-2xl font-semibold text-gray-900 mb-8 tracking-tight">My Orders</h1>

    <div v-if="loading" class="text-center text-gray-300 py-24 text-sm">Loading…</div>
    <div v-else-if="orders.length === 0" class="text-center text-gray-300 py-24 text-sm">
      No orders yet.
    </div>

    <div v-else class="space-y-4">
      <div v-for="order in orders" :key="order.id"
        class="p-5 rounded-2xl border border-gray-100 shadow-sm">
        <div class="flex items-start justify-between gap-4">
          <div>
            <p class="text-sm font-medium text-gray-900">Order #{{ order.id }}</p>
            <p class="text-xs text-gray-400 mt-0.5">{{ formatDate(order.createdAt) }}</p>
          </div>
          <div class="flex items-center gap-3 shrink-0">
            <span :class="statusClass(order.status)"
              class="text-xs px-2.5 py-1 rounded-full font-medium">
              {{ order.status }}
            </span>
            <button v-if="order.status === 'PENDING'" @click="pay(order)"
              class="bg-blue-600 text-white px-3 py-1 rounded-lg text-xs font-medium hover:bg-blue-700 transition">
              Pay now
            </button>
          </div>
        </div>
        <p class="text-sm font-semibold text-gray-900 mt-3">${{ order.totalAmount }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../services/api'

const orders = ref([])
const loading = ref(false)

async function fetchOrders() {
  loading.value = true
  try {
    const res = await api.get('/orders/me', { params: { page: 0, size: 50 } })
    orders.value = res.data.content ?? res.data
  } finally {
    loading.value = false
  }
}

async function pay(order) {
  try {
    await api.post(`/orders/${order.id}/pay`)
    order.status = 'PAID'
  } catch (e) {
    alert(e.response?.data?.message || 'Payment failed.')
  }
}

function formatDate(dt) {
  return new Date(dt).toLocaleString()
}

function statusClass(status) {
  return {
    PENDING:   'bg-yellow-50 text-yellow-600',
    PAID:      'bg-green-50 text-green-600',
    CANCELLED: 'bg-gray-100 text-gray-500',
  }[status] ?? 'bg-gray-100 text-gray-500'
}

onMounted(fetchOrders)
</script>
```

- [ ] **Step 2: Verify**

After checkout, navigate to `/orders`. Order appears with status PENDING and "Pay now" button. Click Pay now → status changes to PAID inline, button disappears.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/OrdersView.vue
git commit -m "feat: add OrdersView with pay button"
```

---

## Task 13: Admin — ProductsView

**Files:**
- Modify: `frontend/src/views/admin/ProductsView.vue`

- [ ] **Step 1: Write `src/views/admin/ProductsView.vue`**

```vue
<template>
  <div>
    <h1 class="text-2xl font-semibold text-gray-900 mb-8 tracking-tight">Admin — Products</h1>

    <!-- Create form -->
    <div class="bg-gray-50 rounded-2xl p-6 mb-10">
      <h2 class="text-sm font-semibold text-gray-700 mb-5">New Product</h2>
      <form @submit.prevent="createProduct" class="grid grid-cols-1 sm:grid-cols-2 gap-4">
        <input v-model="form.name" placeholder="Name" required
          class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-blue-600 transition bg-white" />

        <select v-model="form.categoryId" required
          class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-blue-600 transition bg-white">
          <option value="">Select category</option>
          <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>

        <input v-model="form.price" type="number" step="0.01" min="0" placeholder="Price" required
          class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-blue-600 transition bg-white" />

        <input v-model="form.initialStock" type="number" min="0" placeholder="Initial stock" required
          class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-blue-600 transition bg-white" />

        <input v-model="form.imageUrl" placeholder="Image URL (optional)"
          class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-blue-600 transition bg-white" />

        <input v-model="form.description" placeholder="Description (optional)"
          class="border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none focus:ring-2 focus:ring-blue-600 transition bg-white" />

        <p v-if="createError" class="sm:col-span-2 text-sm text-red-500">{{ createError }}</p>

        <button type="submit" :disabled="creating"
          class="sm:col-span-2 bg-blue-600 text-white rounded-xl py-2.5 text-sm font-medium
                 hover:bg-blue-700 disabled:opacity-50 transition">
          {{ creating ? 'Creating…' : 'Create Product' }}
        </button>
      </form>
    </div>

    <!-- Product list -->
    <div v-if="loading" class="text-center text-gray-300 py-10 text-sm">Loading…</div>
    <div v-else class="space-y-3">
      <div v-for="p in products" :key="p.id"
        class="flex items-center gap-4 p-4 rounded-xl border border-gray-100">
        <div class="flex-1 min-w-0">
          <p class="text-sm font-medium text-gray-900 truncate">{{ p.name }}</p>
          <p class="text-xs text-gray-400 mt-0.5">
            ${{ p.price }} · {{ p.availableStock }}/{{ p.totalStock }} stock
          </p>
        </div>
        <select :value="p.status" @change="setStatus(p, $event.target.value)"
          class="border border-gray-200 rounded-lg px-2.5 py-1 text-xs outline-none
                 focus:ring-2 focus:ring-blue-600 bg-white">
          <option value="ACTIVE">ACTIVE</option>
          <option value="INACTIVE">INACTIVE</option>
        </select>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import api from '../../services/api'

const products = ref([])
const categories = ref([])
const loading = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive({
  name: '', categoryId: '', price: '', initialStock: '', imageUrl: '', description: ''
})

async function fetchAll() {
  loading.value = true
  try {
    const [pr, cr] = await Promise.all([
      api.get('/products', { params: { page: 0, size: 100 } }),
      api.get('/categories'),
    ])
    products.value = pr.data.content ?? pr.data
    categories.value = cr.data
  } finally {
    loading.value = false
  }
}

async function createProduct() {
  createError.value = ''
  creating.value = true
  try {
    await api.post('/products', {
      name: form.name,
      categoryId: Number(form.categoryId),
      price: Number(form.price),
      initialStock: Number(form.initialStock),
      imageUrl: form.imageUrl || null,
      description: form.description || null,
    })
    Object.assign(form, { name: '', categoryId: '', price: '', initialStock: '', imageUrl: '', description: '' })
    await fetchAll()
  } catch (e) {
    createError.value = e.response?.data?.message || 'Failed to create product.'
  } finally {
    creating.value = false
  }
}

async function setStatus(product, status) {
  try {
    await api.put(`/products/${product.id}`, {
      name: product.name,
      description: product.description,
      price: product.price,
      categoryId: product.categoryId,
      imageUrl: product.imageUrl,
      status,
    })
    product.status = status
  } catch {
    alert('Failed to update status.')
  }
}

onMounted(fetchAll)
</script>
```

- [ ] **Step 2: Verify (requires ADMIN role)**

Log in as admin. Navigate to `/admin/products`. Create a product — it appears in the list below. Change status dropdown → API call fires, product status updates in UI.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/admin/ProductsView.vue
git commit -m "feat: add admin ProductsView with create form and status toggle"
```

---

## Task 14: Admin — InventoryView

**Files:**
- Modify: `frontend/src/views/admin/InventoryView.vue`

- [ ] **Step 1: Write `src/views/admin/InventoryView.vue`**

```vue
<template>
  <div class="max-w-md mx-auto">
    <h1 class="text-2xl font-semibold text-gray-900 mb-8 tracking-tight">Admin — Inventory</h1>

    <form @submit.prevent="submit" class="space-y-5">
      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">Product</label>
        <select v-model="productId" required
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 transition bg-white">
          <option value="">Select product</option>
          <option v-for="p in products" :key="p.id" :value="p.id">
            {{ p.name }} ({{ p.availableStock }} available)
          </option>
        </select>
      </div>

      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">
          Delta <span class="font-normal text-gray-400">(+ restock, − damage/correction)</span>
        </label>
        <input v-model="delta" type="number" required placeholder="e.g. 50 or -3"
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 transition" />
      </div>

      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">Reason</label>
        <select v-model="reason" required
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 transition bg-white">
          <option value="restock">Restock</option>
          <option value="correction">Correction</option>
          <option value="damaged">Damaged</option>
        </select>
      </div>

      <p v-if="message" :class="success ? 'text-green-600' : 'text-red-500'" class="text-sm">
        {{ message }}
      </p>

      <button type="submit" :disabled="submitting"
        class="w-full bg-blue-600 text-white rounded-xl py-2.5 text-sm font-medium
               hover:bg-blue-700 disabled:opacity-50 transition">
        {{ submitting ? 'Applying…' : 'Apply Adjustment' }}
      </button>
    </form>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../../services/api'

const products = ref([])
const productId = ref('')
const delta = ref('')
const reason = ref('restock')
const submitting = ref(false)
const message = ref('')
const success = ref(false)

async function fetchProducts() {
  const res = await api.get('/products', { params: { page: 0, size: 100 } })
  products.value = res.data.content ?? res.data
}

async function submit() {
  message.value = ''
  submitting.value = true
  try {
    await api.patch(`/products/${productId.value}/inventory`, {
      delta: Number(delta.value),
      reason: reason.value,
    })
    success.value = true
    message.value = 'Inventory updated.'
    delta.value = ''
    await fetchProducts()
  } catch (e) {
    success.value = false
    message.value = e.response?.data?.message || 'Failed to update inventory.'
  } finally {
    submitting.value = false
  }
}

onMounted(fetchProducts)
</script>
```

- [ ] **Step 2: Verify**

Navigate to `/admin/inventory`. Select a product, enter delta (e.g. `10`), select reason, submit. Success message appears and the product's available count updates in the dropdown.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/admin/InventoryView.vue
git commit -m "feat: add admin InventoryView for stock delta adjustments"
```

---

## Task 15: Admin — OrdersView

**Files:**
- Modify: `frontend/src/views/admin/OrdersView.vue`

- [ ] **Step 1: Write `src/views/admin/OrdersView.vue`**

```vue
<template>
  <div>
    <h1 class="text-2xl font-semibold text-gray-900 mb-8 tracking-tight">Admin — All Orders</h1>

    <div v-if="loading" class="text-center text-gray-300 py-24 text-sm">Loading…</div>
    <div v-else-if="orders.length === 0" class="text-center text-gray-300 py-24 text-sm">
      No orders.
    </div>

    <div v-else class="space-y-3">
      <div v-for="order in orders" :key="order.id"
        class="flex items-center gap-4 p-4 rounded-xl border border-gray-100">
        <div class="flex-1 min-w-0">
          <p class="text-sm font-medium text-gray-900">Order #{{ order.id }}</p>
          <p class="text-xs text-gray-400 mt-0.5">
            User {{ order.userId }} · {{ formatDate(order.createdAt) }}
          </p>
        </div>
        <p class="text-sm font-semibold shrink-0">${{ order.totalAmount }}</p>
        <span :class="statusClass(order.status)"
          class="text-xs px-2.5 py-1 rounded-full font-medium shrink-0">
          {{ order.status }}
        </span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import api from '../../services/api'

const orders = ref([])
const loading = ref(false)

async function fetchOrders() {
  loading.value = true
  try {
    const res = await api.get('/orders', { params: { page: 0, size: 50 } })
    orders.value = res.data.content ?? res.data
  } finally {
    loading.value = false
  }
}

function formatDate(dt) {
  return new Date(dt).toLocaleString()
}

function statusClass(status) {
  return {
    PENDING:   'bg-yellow-50 text-yellow-600',
    PAID:      'bg-green-50 text-green-600',
    CANCELLED: 'bg-gray-100 text-gray-500',
  }[status] ?? 'bg-gray-100 text-gray-500'
}

onMounted(fetchOrders)
</script>
```

- [ ] **Step 2: Verify**

Navigate to `/admin/orders`. All orders across all users appear with userId, amount, and status badge.

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/admin/OrdersView.vue
git commit -m "feat: add admin OrdersView showing all orders"
```

---

## Task 16: Final Smoke Test + Update PROGRESS.md

- [ ] **Step 1: Full end-to-end test**

With Spring Boot running on `:8080` and `npm run dev` on `:5173`:

1. Register a new user → redirected to `/`, nav shows Cart/Orders/Sign out
2. Browse products on `/` — search and category filter work
3. Add 2–3 items to cart → badge increments
4. Go to `/cart` — items appear, qty controls work, total updates
5. Click Checkout → redirected to `/orders`, order status is PENDING
6. Click Pay now → status changes to PAID
7. Sign out → redirected to `/login`
8. Log in as ADMIN → nav shows Admin links
9. Go to `/admin/products` → create a product, appears in list, toggle status
10. Go to `/admin/inventory` → adjust stock, success message
11. Go to `/admin/orders` → all orders visible

- [ ] **Step 2: Update PROGRESS.md**

In `PROGRESS.md`, change Step 12 status from `🔄 In Progress` to `✅ Complete` and add a summary of what was built.

- [ ] **Step 3: Final commit**

```bash
git add PROGRESS.md
git commit -m "docs: mark Step 12 Vue frontend complete"
```
