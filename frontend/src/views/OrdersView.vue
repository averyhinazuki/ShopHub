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
