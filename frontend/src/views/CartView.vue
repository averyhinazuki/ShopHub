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
  try {
    // CartItemRequest requires productId even on PUT (same DTO as POST)
    await api.put(`/cart/items/${item.id}`, { productId: item.productId, quantity: newQty })
    item.quantity = newQty
  } catch {
    checkoutError.value = 'Failed to update quantity.'
  }
}

async function removeItem(item) {
  try {
    await api.delete(`/cart/items/${item.id}`)
    items.value = items.value.filter(i => i.id !== item.id)
    cart.decrement()
  } catch {
    checkoutError.value = 'Failed to remove item.'
  }
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
