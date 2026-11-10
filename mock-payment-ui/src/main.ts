import { createApp, reactive } from 'vue'
import { createRouter, createWebHistory } from 'vue-router'
import App from './App.vue'
import LoginView from './views/LoginView.vue'
import DashboardView from './views/DashboardView.vue'
import MerchantView from './views/MerchantView.vue'
import TransactionView from './views/TransactionView.vue'
import UserView from './views/UserView.vue'
import AuditView from './views/AuditView.vue'
import CashierView from './views/CashierView.vue'
import type { Session } from './types'
import { api } from './api'
import './style.css'

export const authState = reactive<{ session: Session | null; checked: boolean }>({ session: null, checked: false })

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/cashier/:token', component: CashierView, meta: { public: true } },
    { path: '/', component: DashboardView },
    { path: '/merchants', component: MerchantView, meta: { authority: 'MERCHANT_MANAGE' } },
    { path: '/transactions', component: TransactionView, meta: { authority: 'TRANSACTION_READ' } },
    { path: '/users', component: UserView, meta: { authority: 'ADMIN_USER_MANAGE' } },
    { path: '/audit', component: AuditView, meta: { authority: 'AUDIT_READ' } }
  ]
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (!authState.checked) {
    try { authState.session = await api<Session>('/api/auth/session') } catch { authState.session = null }
    authState.checked = true
  }
  if (!authState.session) return { path: '/login', query: { redirect: to.fullPath } }
  const authority = to.meta.authority as string | undefined
  if (authority && !authState.session.authorities.includes(authority)) return '/'
  return true
})

createApp(App).use(router).mount('#app')
