<script setup lang="ts">
import {ref} from 'vue'
import {useRoute, useRouter} from 'vue-router'
import {api, initializeCsrf} from '../api'
import {authState} from '../main'
import type {Session} from '../types'
import {languageLabel, switchLanguage, t} from '../i18n'

const username = ref('payment-admin')
const password = ref('demo-password')
const loading = ref(false)
const error = ref('')
const router = useRouter()
const route = useRoute()

async function login() {
  loading.value = true
  error.value = ''
  try {
    await initializeCsrf()
    authState.session = await api<Session>('/api/auth/login', {
      method: 'POST', body: JSON.stringify({username: username.value, password: password.value})
    })
    authState.checked = true
    await router.push(String(route.query.redirect || '/'))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('loginFailed')
  } finally {
    loading.value = false
  }
}

function useAccount(value: string) {
  username.value = value;
  password.value = 'demo-password'
}
</script>

<template>
  <div class="login-page">
    <section class="login-story">
      <div class="brand light"><span class="brand-mark">C</span>
        <div><strong>CommerceFlow Pay</strong><small>{{ t('loginLab') }}</small></div>
      </div>
      <div class="story-copy"><span class="eyebrow">{{ t('chapter') }}</span>
        <h1>{{ t('loginLead') }}<br><em>{{ t('loginAccent') }}</em></h1>
        <p>{{ t('loginDescription') }}</p></div>
      <div class="story-grid">
        <div><b>3</b><span>{{ t('simulatedChannels') }}</span></div>
        <div><b>AES-GCM</b><span>{{ t('credentialEncryption') }}</span></div>
        <div><b>RBAC</b><span>{{ t('leastPrivilege') }}</span></div>
      </div>
    </section>
    <section class="login-panel">
      <form class="login-card" @submit.prevent="login">
        <div class="public-language">
          <button class="language-toggle" type="button" @click="switchLanguage">{{ languageLabel }}</button>
        </div>
        <span class="eyebrow">{{ t('secureAdminAccess') }}</span>
        <h2>{{ t('loginTitle') }}</h2>
        <p class="muted">{{ t('loginHint') }}</p>
        <label>{{ t('username') }}<input v-model="username" autocomplete="username"
                                         placeholder="payment-admin"/></label>
        <label>{{ t('password') }}<input v-model="password" type="password" autocomplete="current-password"
                                         :placeholder="t('passwordPlaceholder')"/></label>
        <div v-if="error" class="notice error">{{ error }}</div>
        <button class="primary full" :disabled="loading">{{ loading ? t('verifying') : t('secureLogin') }}</button>
        <div class="demo-accounts"><span>{{ t('demoAccounts') }} <code>demo-password</code></span>
          <button type="button" @click="useAccount('payment-admin')">{{ t('administrator') }}</button>
          <button type="button" @click="useAccount('payment-operator')">{{ t('operator') }}</button>
          <button type="button" @click="useAccount('payment-auditor')">{{ t('auditor') }}</button>
        </div>
        <small class="security-foot">{{ t('loginProtection') }}</small>
      </form>
    </section>
  </div>
</template>
