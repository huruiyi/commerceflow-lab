<script setup lang="ts">
import { computed, ref } from 'vue'
import QRCode from 'qrcode'
import { api, saveStepUpToken } from '../api'
import { authState } from '../main'

const password = ref('')
const code = ref('')
const enrollment = ref<{ secret: string; otpauthUri: string } | null>(null)
const qr = ref('')
const recoveryCodes = ref<string[]>([])
const stepUp = ref<{ token: string; expiresAt: string; factor: string } | null>(null)
const error = ref('')
const message = ref('')
const enabled = computed(() => authState.session?.mfaEnabled)

async function enroll() {
  error.value = ''; message.value = ''
  try {
    const value = await api<{ secret: string; otpauthUri: string }>('/api/auth/mfa/enroll', { method: 'POST', body: JSON.stringify({ password: password.value }) })
    enrollment.value = value
    qr.value = await QRCode.toDataURL(value.otpauthUri, { width: 220, margin: 1 })
    message.value = '请使用验证器扫码，再输入 6 位验证码确认。'
  } catch (e) { error.value = e instanceof Error ? e.message : '绑定失败' }
}
async function confirm() {
  try {
    const result = await api<{ codes: string[]; notice: string }>('/api/auth/mfa/confirm', { method: 'POST', body: JSON.stringify({ code: code.value }) })
    recoveryCodes.value = result.codes
    authState.session = await api('/api/auth/session')
    message.value = result.notice
  } catch (e) { error.value = e instanceof Error ? e.message : '确认失败' }
}
async function authorize() {
  error.value = ''
  try {
    const value = await api<{ token: string; expiresAt: string; factor: string }>('/api/auth/step-up', { method: 'POST', body: JSON.stringify({ password: password.value, code: code.value }) })
    stepUp.value = value
    saveStepUpToken(value.token)
    message.value = `已生成一次性 Step-up 令牌（${value.factor}），5 分钟内在目标页执行一次高危操作。`
  } catch (e) { error.value = e instanceof Error ? e.message : '二次认证失败' }
}
async function copyCodes() { await navigator.clipboard.writeText(recoveryCodes.value.join('\n')) }
</script>

<template>
  <div class="page-title"><div><span class="eyebrow">SECURITY CENTER · 05A-3</span><h1>账号安全与二次认证</h1><p>TOTP、一次性恢复码和高危操作 Step-up 令牌的完整教学流程。</p></div><span :class="['badge', enabled ? 'success' : 'pending']">{{ enabled ? 'MFA ENABLED' : 'MFA REQUIRED' }}</span></div>
  <div v-if="error" class="notice error">{{ error }}</div><div v-if="message" class="notice">{{ message }}</div>
  <div class="content-grid">
    <section class="card"><span class="eyebrow">1 · ENROLLMENT</span><h3>绑定验证器</h3><p class="muted">支持 Google Authenticator、Microsoft Authenticator 等 RFC 6238 兼容应用。</p><label>当前密码<input v-model="password" type="password" autocomplete="current-password" /></label><button class="primary full" @click="enroll">生成 TOTP 密钥</button></section>
    <section class="card"><span class="eyebrow">2 · VERIFY</span><h3>扫码并确认</h3><div v-if="enrollment" style="text-align:center"><img :src="qr" alt="TOTP QR code" width="220" height="220" /><p class="mono small">{{ enrollment.secret }}</p></div><div v-else class="empty">先生成绑定密钥</div><label>6 位 TOTP 验证码<input v-model="code" inputmode="numeric" maxlength="14" placeholder="123456 或恢复码" /></label><button class="secondary full" :disabled="!enrollment" @click="confirm">确认启用 MFA</button></section>
    <section class="card"><span class="eyebrow">3 · STEP-UP</span><h3>授权一次高危操作</h3><p class="muted">令牌存入 Redis，5 分钟过期且使用后立即删除，不可重放。</p><label>TOTP / 恢复码<input v-model="code" /></label><button class="primary full" @click="authorize">生成 Step-up 令牌</button><div v-if="stepUp" class="notice success">有效至 {{ new Date(stepUp.expiresAt).toLocaleTimeString() }}，请现在进入商户或用户页执行操作。</div></section>
  </div>
  <section v-if="recoveryCodes.length" class="secret-panel"><div><span class="eyebrow">RECOVERY CODES</span><h3>立即保存恢复码</h3><p>数据库仅保存 BCrypt 哈希，明文不能再次查看。</p></div><code style="white-space:pre-wrap">{{ recoveryCodes.join('\n') }}</code><button @click="copyCodes">复制全部</button></section>
</template>
