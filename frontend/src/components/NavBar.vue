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
