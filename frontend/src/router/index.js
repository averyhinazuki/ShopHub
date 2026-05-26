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
