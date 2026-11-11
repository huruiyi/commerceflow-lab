<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, takeStepUpToken } from '../api'
import type { IssuedCredential, Merchant } from '../types'
import { enumLabel, t } from '../i18n'

const merchants = ref<Merchant[]>([])
const showCreate = ref(false)
const issued = ref<IssuedCredential | null>(null)
const error = ref('')
const form = ref({ displayName: 'CommerceFlow Lab', notifyUrl: 'http://backend:8080/api/payment/notifications', signingAlgorithm: 'HMAC_SHA256', merchantPublicKey: '' })
const load = async () => merchants.value = await api('/api/admin/merchants')
onMounted(load)

async function create() {
  error.value = ''
  try { issued.value = await api('/api/admin/merchants', { method: 'POST', body: JSON.stringify(form.value) }); showCreate.value = false; await load() }
  catch (e) { error.value = e instanceof Error ? e.message : t('createFailed') }
}
async function rotate(item: Merchant) {
  const token = takeStepUpToken()
  if (!token) { error.value = t('stepUpFirst'); return }
  try { issued.value = await api(`/api/admin/merchants/${item.id}/rotate-secret`, { method: 'POST', headers: { 'X-Step-Up-Token': token } }); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : t('rotateFailed') }
}
async function toggle(item: Merchant) {
  const token = takeStepUpToken()
  if (!token) { error.value = t('stepUpFirst'); return }
  try { await api(`/api/admin/merchants/${item.id}/enabled/${!item.enabled}`, { method: 'POST', headers: { 'X-Step-Up-Token': token } }); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : t('operationFailed') }
}
async function downloadPublicKey() {
  const text = await api<string>('/api/admin/merchants/platform-public-key')
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }))
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'commerceflow-pay-platform-public.pem'; anchor.click(); URL.revokeObjectURL(url)
}
async function copySecret() {
  if (issued.value) await window.navigator.clipboard.writeText(issued.value.appSecret)
}
</script>

<template>
  <div class="page-title"><div><span class="eyebrow">{{ t('merchantOnboarding') }}</span><h1>{{ t('merchantTitle') }}</h1><p>{{ t('merchantDescription') }}</p></div><div class="actions"><button class="secondary" @click="downloadPublicKey">{{ t('downloadPublicKey') }}</button><button class="primary" @click="showCreate = true">{{ t('createMerchant') }}</button></div></div>
  <div v-if="error" class="notice error">{{ error }}</div>
  <div v-if="issued" class="secret-panel"><div><span class="eyebrow">{{ t('oneTimeCredential') }}</span><h3>{{ t('copySecretNow') }}</h3><p>{{ issued.warning }}</p></div><div class="secret-value"><code>{{ issued.appSecret }}</code><button @click="copySecret">{{ t('copy') }}</button></div><button class="ghost" @click="issued = null">{{ t('saved') }}</button></div>
  <div class="merchant-grid">
    <article v-for="item in merchants" :key="item.id" class="card merchant-card">
      <div class="card-head"><div class="merchant-icon">{{ item.displayName.slice(0, 1) }}</div><span :class="['badge', item.enabled ? 'success' : 'closed']">{{ enumLabel(item.enabled ? 'ENABLED' : 'DISABLED') }}</span></div>
      <h3>{{ item.displayName }}</h3><p class="mono">{{ item.merchantNo }}</p>
      <dl><dt>App ID</dt><dd class="mono">{{ item.appId }}</dd><dt>{{ t('signingAlgorithm') }}</dt><dd><span class="badge pending">{{ item.signingAlgorithm }}</span></dd><dt>App Secret</dt><dd>{{ item.secretMasked }} · v{{ item.credentialVersion }}</dd><dt>{{ t('callbackAllowlist') }}</dt><dd>{{ item.notifyUrl || t('notConfigured') }}</dd><dt>{{ t('merchantPublicKey') }}</dt><dd>{{ item.merchantPublicKey ? t('rsaRegistered') : t('hmacNotApplicable') }}</dd></dl>
      <div class="card-actions"><button class="secondary" @click="rotate(item)">{{ t('rotateSecret') }}</button><button class="danger-link" @click="toggle(item)">{{ item.enabled ? t('disable') : t('enable') }}</button></div>
    </article>
    <div v-if="!merchants.length" class="empty card">{{ t('noMerchants') }}</div>
  </div>
  <div v-if="showCreate" class="modal-backdrop"><form class="modal" @submit.prevent="create"><div class="card-head"><div><span class="eyebrow">{{ t('newMerchant') }}</span><h2>{{ t('createSimulatedMerchant') }}</h2></div><button type="button" class="ghost" @click="showCreate = false">✕</button></div><label>{{ t('displayName') }}<input v-model="form.displayName" required /></label><label>{{ t('notifyUrl') }}<input v-model="form.notifyUrl" /></label><label>{{ t('requestSigningAlgorithm') }}<select v-model="form.signingAlgorithm"><option value="HMAC_SHA256">HMAC-SHA256</option><option value="RSA_SHA256">RSA-SHA256</option></select></label><label v-if="form.signingAlgorithm === 'RSA_SHA256'">{{ t('merchantRsaPublicKey') }}<textarea v-model="form.merchantPublicKey" rows="6" required placeholder="-----BEGIN PUBLIC KEY-----"></textarea></label><div class="notice">{{ t('credentialNotice') }}</div><button class="primary full">{{ t('issueCredential') }}</button></form></div>
</template>
