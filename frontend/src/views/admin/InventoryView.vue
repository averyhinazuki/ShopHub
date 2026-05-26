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
