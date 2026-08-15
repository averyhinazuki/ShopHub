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

      <!-- Checkout is asynchronous: the server returns 202 with a checkoutId and
           the order is created by a Kafka consumer, so the result arrives by
           polling rather than in the response. -->
      <p v-if="checkingOut" class="mt-4 text-sm text-gray-400 flex items-center gap-2">
        <span class="inline-block w-3 h-3 rounded-full border-2 border-gray-300 border-t-blue-600 animate-spin"></span>
        Placing your order — this usually takes a moment.
      </p>

      <p v-if="checkoutError" class="mt-4 text-sm text-red-500">{{ checkoutError }}</p>

      <p v-if="checkoutNotice" class="mt-4 text-sm text-amber-600">
        {{ checkoutNotice }}
        <RouterLink to="/orders" class="underline ml-1">Check your orders</RouterLink>
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, RouterLink } from 'vue-router'
import api from '../services/api'
import { useCartStore } from '../stores/cart'

const items = ref([])
const loading = ref(false)
const checkingOut = ref(false)
const checkoutError = ref('')
const checkoutNotice = ref('')
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

// ── Async checkout ──────────────────────────────────────────────────────────
// POST /orders/checkout returns 202 Accepted with a checkoutId; the order is
// created later by a Kafka consumer. This view previously discarded that
// response and navigated straight to /orders, which meant:
//   - failures were completely silent (axios resolves on 2xx, so 202 took the
//     happy path and the catch never ran; a sold-out checkout and a successful
//     one looked identical)
//   - the user, seeing no order, retried — and a retry mints a NEW checkoutId,
//     which the consumer's dedup guard cannot recognise as the same intent,
//     producing two real orders for one intended purchase
//   - even a successful checkout showed an empty order list, because /orders was
//     fetched before the consumer had committed anything
//
// So the result has to be polled. Every branch below terminates.

const POLL_INITIAL_MS = 400
const POLL_MAX_MS     = 3000
const POLL_BUDGET_MS  = 45000   // total, not per attempt

let pollCancelled = false

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))

/**
 * Polls until the checkout reaches a terminal state, or we give up.
 * Bounded client-side on purpose, independent of what the server promises.
 */
async function pollCheckoutStatus(checkoutId) {
  const deadline = Date.now() + POLL_BUDGET_MS
  let delay = POLL_INITIAL_MS

  while (Date.now() < deadline) {
    if (pollCancelled) return { outcome: 'ABANDONED' }
    await sleep(delay)
    delay = Math.min(delay * 1.5, POLL_MAX_MS)

    try {
      const { data } = await api.get(`/orders/checkout-status/${checkoutId}`)
      if (data.status === 'SUCCESS') return { outcome: 'SUCCESS', orderId: data.orderId }
      if (data.status === 'FAILED')  return { outcome: 'FAILED', reason: data.failureReason }
      // PENDING — the consumer hasn't finished yet. Keep waiting.
    } catch (e) {
      // 404 means the status record is gone: it expired, or never existed. The
      // server deliberately does not report that as PENDING, because absence of
      // information is not an ongoing state — treating it as one is what made
      // this loop non-terminating in the first place.
      if (e.response?.status === 404) return { outcome: 'UNKNOWN' }
      // Anything else (network blip, 5xx) is worth another attempt within budget.
    }
  }
  return { outcome: 'TIMEOUT' }
}

async function checkout() {
  checkoutError.value = ''
  checkoutNotice.value = ''
  checkingOut.value = true
  pollCancelled = false

  try {
    const { data } = await api.post('/orders/checkout')
    const checkoutId = data?.checkoutId
    if (!checkoutId) {
      // Shouldn't happen, but never poll a URL built from undefined.
      checkoutNotice.value = 'Your order was accepted but we could not track it.'
      return
    }

    const result = await pollCheckoutStatus(checkoutId)

    switch (result.outcome) {
      case 'SUCCESS':
        // Only now is the server cart actually empty — persistOrder clears it in
        // the same transaction that creates the order. Resetting earlier made the
        // badge disagree with the server on every failure.
        cart.reset()
        router.push('/orders')
        break
      case 'FAILED':
        await fetchCart()   // the server still holds the items
        checkoutError.value = result.reason || 'Checkout failed. Please try again.'
        break
      case 'UNKNOWN':
        checkoutNotice.value = 'We lost track of this checkout — it may still have gone through.'
        break
      case 'TIMEOUT':
        checkoutNotice.value = 'This is taking longer than expected. Your order may still complete.'
        break
      // ABANDONED: the user navigated away; say nothing.
    }
  } catch (e) {
    // Only synchronous failures reach here — an empty cart (400), auth, network.
    // Sold-out is decided asynchronously and shows up as FAILED above, which is
    // why the old copy here ("some items may be sold out") named the one failure
    // this branch cannot detect.
    checkoutError.value = e.response?.data?.error || 'Could not start checkout. Please try again.'
  } finally {
    checkingOut.value = false
  }
}

onMounted(fetchCart)
onUnmounted(() => { pollCancelled = true })
</script>
