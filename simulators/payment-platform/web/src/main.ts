import {createApp, reactive} from 'vue'
import {createRouter, createWebHistory} from 'vue-router'
import Antd from 'ant-design-vue'
import VxeUITable from 'vxe-table'
import App from './App.vue'
import type {Session} from './types'
import {api} from './api'
import './style.css'
import './responsive.css'
import 'ant-design-vue/dist/reset.css'
import 'vxe-table/lib/style.css'

export const authState = reactive<{ session: Session | null; checked: boolean }>({session: null, checked: false})

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {path: '/login', component: () => import('./views/LoginView.vue'), meta: {public: true}},
    {path: '/cashier/:token', component: () => import('./views/CashierView.vue'), meta: {public: true}},
    {path: '/', component: () => import('./views/DashboardView.vue')},
    {path: '/merchants', component: () => import('./views/MerchantView.vue'), meta: {authority: 'MERCHANT_MANAGE'}},
    {
      path: '/transactions',
      component: () => import('./views/TransactionView.vue'),
      meta: {authority: 'TRANSACTION_READ'}
    },
    {path: '/refunds', component: () => import('./views/RefundView.vue'), meta: {authority: 'REFUND_READ'}},
    {path: '/users', component: () => import('./views/UserView.vue'), meta: {authority: 'ADMIN_USER_MANAGE'}},
    {path: '/audit', component: () => import('./views/AuditView.vue'), meta: {authority: 'AUDIT_READ'}},
    {path: '/security', component: () => import('./views/SecurityView.vue')}
  ]
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  if (!authState.checked) {
    try {
      authState.session = await api<Session>('/api/auth/session')
    } catch {
      authState.session = null
    }
    authState.checked = true
  }
  if (!authState.session) return {path: '/login', query: {redirect: to.fullPath}}
  const authority = to.meta.authority as string | undefined
  if (authority && !authState.session.authorities.includes(authority)) return '/'
  return true
})

createApp(App)
  .use(router)
  .use(Antd)
  .use(VxeUITable)
  .mount('#app')
