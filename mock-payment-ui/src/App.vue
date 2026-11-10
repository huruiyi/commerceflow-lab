<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authState } from './main'
import { api, initializeCsrf } from './api'

const route = useRoute()
const router = useRouter()
const publicPage = computed(() => Boolean(route.meta.public))
const can = (authority: string) => authState.session?.authorities.includes(authority)

async function logout() {
  await initializeCsrf()
  await api<void>('/api/auth/logout', { method: 'POST' })
  authState.session = null
  authState.checked = true
  await router.push('/login')
}
</script>

<template>
  <router-view v-if="publicPage" />
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">M</span><div><strong>MockPay</strong><small>支付平台实验室</small></div></div>
      <nav>
        <router-link to="/">◈ 控制台</router-link>
        <router-link v-if="can('MERCHANT_MANAGE')" to="/merchants">▣ 商户与凭据</router-link>
        <router-link v-if="can('TRANSACTION_READ')" to="/transactions">⇄ 渠道交易</router-link>
        <router-link v-if="can('ADMIN_USER_MANAGE')" to="/users">♙ 后台账号</router-link>
        <router-link v-if="can('AUDIT_READ')" to="/audit">⌕ 审计日志</router-link>
        <router-link to="/security">◈ 安全中心</router-link>
      </nav>
      <div class="security-card"><span class="pulse"></span><div><strong>安全模式</strong><small>Redis Session · CSRF · BCrypt</small></div></div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div><span class="env">LOCAL SIMULATION</span><span class="muted">不会产生真实资金交易</span></div>
        <div class="account"><div class="avatar">{{ authState.session?.displayName.slice(0, 1) }}</div><div><strong>{{ authState.session?.displayName }}</strong><small>{{ authState.session?.username }}</small></div><button class="ghost" @click="logout">退出</button></div>
      </header>
      <div v-if="authState.session?.mfaRequired && !authState.session?.mfaEnabled" class="notice warn">
        此账号必须启用 MFA。请进入「安全中心」绑定 TOTP，否则无法执行密钥轮换、禁用商户和权限变更。
      </div>
      <section class="page"><router-view /></section>
    </main>
  </div>
</template>
