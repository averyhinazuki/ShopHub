import axios from 'axios'
import { useAuthStore } from '../stores/auth'

const api = axios.create({ baseURL: '/api' })

api.interceptors.request.use(config => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.Authorization = `Bearer ${auth.accessToken}`
  }
  return config
})

api.interceptors.response.use(
  res => res,
  async err => {
    const auth = useAuthStore()
    if (err.response?.status === 401 && !err.config._retry) {
      err.config._retry = true
      try {
        await auth.refresh()
        err.config.headers.Authorization = `Bearer ${auth.accessToken}`
        return api(err.config)
      } catch {
        auth.clear()
        window.location.href = '/login'
      }
    }
    return Promise.reject(err)
  }
)

export default api
