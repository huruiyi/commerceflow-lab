<script setup lang="ts">
import {computed} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {authState} from './main'
import {api, initializeCsrf} from './api'
import {languageLabel, switchLanguage, t} from './i18n'

const route = useRoute()
const router = useRouter()
const publicPage = computed(() => Boolean(route.meta.public))
const can = (authority: string) => authState.session?.authorities.includes(authority)

async function logout() {
  await initializeCsrf()
  await api<void>('/api/auth/logout', {method: 'POST'})
  authState.session = null
  authState.checked = true
  await router.push('/login')
}
</script>

<template>
  <router-view v-if="publicPage"/>
  <div v-else class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">C</span>
        <div><strong>CommerceFlow Pay</strong><small>{{ t('loginLab') }}</small></div>
      </div>
      <nav>
        <router-link to="/">◈ {{ t('dashboard') }}</router-link>
        <router-link v-if="can('MERCHANT_MANAGE')" to="/merchants">▣ {{ t('merchants') }}</router-link>
        <router-link v-if="can('TRANSACTION_READ')" to="/transactions">⇄ {{ t('transactions') }}</router-link>
        <router-link v-if="can('REFUND_READ')" to="/refunds">↩ {{ t('refunds') }}</router-link>
        <router-link v-if="can('ADMIN_USER_MANAGE')" to="/users">♙ {{ t('users') }}</router-link>
        <router-link v-if="can('AUDIT_READ')" to="/audit">⌕ {{ t('audit') }}</router-link>
        <router-link to="/security">◈ {{ t('security') }}</router-link>
      </nav>
      <div class="security-card"><span class="pulse"></span>
        <div><strong>{{ t('securityMode') }}</strong><small>Redis Session · CSRF · BCrypt</small></div>
      </div>
    </aside>
    <main class="main">
      <header class="topbar">
        <div><span class="env">{{ t('localSimulation') }}</span><span class="muted">{{ t('noRealFunds') }}</span></div>
        <div class="account">
          <button class="language-toggle" type="button" @click="switchLanguage">{{ languageLabel }}</button>
          <div class="avatar">{{ authState.session?.displayName.slice(0, 1) }}</div>
          <div><strong>{{ authState.session?.displayName }}</strong><small>{{ authState.session?.username }}</small>
          </div>
          <button class="ghost" @click="logout">{{ t('logout') }}</button>
        </div>
      </header>
      <div v-if="authState.session?.mfaRequired && !authState.session?.mfaEnabled" class="notice warn">
        {{ t('mfaNotice') }}
      </div>
      <section class="page">
        <router-view/>
      </section>
    </main>
  </div>
</template>
