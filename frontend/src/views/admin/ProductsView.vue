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
