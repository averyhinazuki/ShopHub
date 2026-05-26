<template>
  <div class="max-w-sm mx-auto mt-24">
    <h1 class="text-2xl font-semibold text-gray-900 mb-8 tracking-tight">Create account</h1>

    <form @submit.prevent="handleRegister" class="space-y-4">
      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">Username</label>
        <input v-model="username" type="text" required autocomplete="username"
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 focus:border-transparent transition" />
      </div>
      <div>
        <label class="block text-xs text-gray-500 mb-1.5 font-medium">Password</label>
        <input v-model="password" type="password" required autocomplete="new-password"
          class="w-full border border-gray-200 rounded-xl px-3.5 py-2.5 text-sm outline-none
                 focus:ring-2 focus:ring-blue-600 focus:border-transparent transition" />
      </div>

      <p v-if="error" class="text-sm text-red-500">{{ error }}</p>

      <button type="submit" :disabled="loading"
        class="w-full bg-blue-600 text-white rounded-xl py-2.5 text-sm font-medium
               hover:bg-blue-700 disabled:opacity-50 transition mt-2">
        {{ loading ? 'Creating account…' : 'Create account' }}
      </button>
    </form>

    <p class="mt-6 text-sm text-gray-400 text-center">
      Already have an account?
      <RouterLink to="/login" class="text-blue-600 hover:underline">Sign in</RouterLink>
    </p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useCartStore } from '../stores/cart'

const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)
const router = useRouter()
const auth = useAuthStore()
const cart = useCartStore()

async function handleRegister() {
  error.value = ''
  loading.value = true
  try {
    await auth.register(username.value, password.value)
    await cart.fetchCount()
    router.push('/')
  } catch (e) {
    error.value = e.response?.data?.message || 'Registration failed.'
  } finally {
    loading.value = false
  }
}
</script>
