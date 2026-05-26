import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import axios from 'axios'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref(localStorage.getItem('accessToken') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const role = ref(localStorage.getItem('role') || '')

  const isLoggedIn = computed(() => !!accessToken.value)
  const isAdmin = computed(() => role.value === 'ADMIN')

  function setTokens(at, rt, userRole) {
    accessToken.value = at
    refreshToken.value = rt
    role.value = userRole
    localStorage.setItem('accessToken', at)
    localStorage.setItem('refreshToken', rt)
    localStorage.setItem('role', userRole)
  }

  function clear() {
    accessToken.value = ''
    refreshToken.value = ''
    role.value = ''
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('role')
  }

  function decodeRole(token) {
    const b64 = token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64))
    return payload.role || ''
  }

  async function login(username, password) {
    const res = await axios.post('/api/auth/login', { username, password })
    const { accessToken: at, refreshToken: rt } = res.data
    setTokens(at, rt, decodeRole(at))
  }

  async function register(username, password) {
    const res = await axios.post('/api/auth/register', { username, password })
    const { accessToken: at, refreshToken: rt } = res.data
    setTokens(at, rt, decodeRole(at))
  }

  async function logout() {
    try {
      await axios.post('/api/auth/logout',
        { refreshToken: refreshToken.value },
        { headers: { Authorization: `Bearer ${accessToken.value}` } }
      )
    } finally {
      clear()
    }
  }

  async function refresh() {
    const res = await axios.post('/api/auth/refresh', { refreshToken: refreshToken.value })
    const { accessToken: at, refreshToken: rt } = res.data
    setTokens(at, rt, decodeRole(at))
  }

  return { accessToken, refreshToken, role, isLoggedIn, isAdmin, login, register, logout, refresh, clear }
})
