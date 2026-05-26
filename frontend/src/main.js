import { createApp } from 'vue'
import { createPinia } from 'pinia'
import router from './router'
import './assets/style.css'
import App from './App.vue'

const app = createApp(App)
app.use(createPinia()) // Pinia MUST be registered before router (guards use auth store)
app.use(router)
app.mount('#app')
