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
