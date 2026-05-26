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
