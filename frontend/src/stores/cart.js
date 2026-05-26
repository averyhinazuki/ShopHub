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
