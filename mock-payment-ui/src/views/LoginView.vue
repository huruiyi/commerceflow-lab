<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, initializeCsrf } from '../api'
import { authState } from '../main'
import type { Session } from '../types'

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
      method: 'POST', body: JSON.stringify({ username: username.value, password: password.value })
    })
    authState.checked = true
    await router.push(String(route.query.redirect || '/'))
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败，请检查输入后重试'
  } finally { loading.value = false }
}

function useAccount(value: string) { username.value = value; password.value = 'demo-password' }
</script>

<template>
  <div class="login-page">
    <section class="login-story">
      <div class="brand light"><span class="brand-mark">C</span><div><strong>CommerceFlow Pay</strong><small>商流支付基础设施实验室</small></div></div>
      <div class="story-copy"><span class="eyebrow">05A-2 · 独立支付平台</span><h1>看懂一笔支付，<br><em>从凭据到渠道交易。</em></h1><p>独立数据库、Redis Session、三渠道适配器与最小权限后台。所有操作仅发生在本地教学环境。</p></div>
      <div class="story-grid"><div><b>3</b><span>模拟渠道</span></div><div><b>AES-GCM</b><span>凭据加密</span></div><div><b>RBAC</b><span>最小权限</span></div></div>
    </section>
    <section class="login-panel">
      <form class="login-card" @submit.prevent="login">
        <span class="eyebrow">SECURE ADMIN ACCESS</span><h2>登录支付后台</h2><p class="muted">使用独立后台账号，不与商城 Cookie 共用。</p>
        <label>用户名<input v-model="username" autocomplete="username" placeholder="payment-admin" /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" placeholder="至少 12 位" /></label>
        <div v-if="error" class="notice error">{{ error }}</div>
        <button class="primary full" :disabled="loading">{{ loading ? '正在验证…' : '安全登录' }}</button>
        <div class="demo-accounts"><span>演示账号，密码均为 <code>demo-password</code></span><button type="button" @click="useAccount('payment-admin')">管理员</button><button type="button" @click="useAccount('payment-operator')">运营员</button><button type="button" @click="useAccount('payment-auditor')">审计员</button></div>
        <small class="security-foot">连续 5 次失败临时锁定 · IP/账号 Redis 限流 · Session 固定攻击防护</small>
      </form>
    </section>
  </div>
</template>
