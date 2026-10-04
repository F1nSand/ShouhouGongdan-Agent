import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import './style.css'
import { createAppRouter } from './router'
import { useAuthStore } from './stores/auth'
import { useConversationStore } from './stores/conversation'

const pinia = createPinia()
const router = createAppRouter(pinia)
const app = createApp(App)
app.use(pinia)
app.use(router)
app.mount('#app')

window.addEventListener('auth:expired', () => {
  useConversationStore(pinia).reset()
  useAuthStore(pinia).logout()
  void router.push({ name: 'login', query: { expired: '1' } })
})
