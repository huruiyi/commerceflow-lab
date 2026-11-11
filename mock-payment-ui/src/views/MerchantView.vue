<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, takeStepUpToken } from '../api'
import type { IssuedCredential, Merchant } from '../types'

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
  catch (e) { error.value = e instanceof Error ? e.message : '创建失败' }
}
async function rotate(item: Merchant) {
  const token = takeStepUpToken()
  if (!token) { error.value = '请先在安全中心完成二次认证，再立即执行此操作。'; return }
  try { issued.value = await api(`/api/admin/merchants/${item.id}/rotate-secret`, { method: 'POST', headers: { 'X-Step-Up-Token': token } }); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '轮换失败' }
}
async function toggle(item: Merchant) {
  const token = takeStepUpToken()
  if (!token) { error.value = '请先在安全中心完成二次认证，再立即执行此操作。'; return }
  try { await api(`/api/admin/merchants/${item.id}/enabled/${!item.enabled}`, { method: 'POST', headers: { 'X-Step-Up-Token': token } }); await load() }
  catch (e) { error.value = e instanceof Error ? e.message : '操作失败' }
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
  <div class="page-title"><div><span class="eyebrow">MERCHANT ONBOARDING</span><h1>商户与应用凭据</h1><p>管理 App ID、一次性 Secret、公钥与回调地址。</p></div><div class="actions"><button class="secondary" @click="downloadPublicKey">下载平台公钥</button><button class="primary" @click="showCreate = true">＋ 创建商户</button></div></div>
  <div v-if="error" class="notice error">{{ error }}</div>
  <div v-if="issued" class="secret-panel"><div><span class="eyebrow">ONE-TIME CREDENTIAL</span><h3>立即复制 App Secret</h3><p>{{ issued.warning }}</p></div><div class="secret-value"><code>{{ issued.appSecret }}</code><button @click="copySecret">复制</button></div><button class="ghost" @click="issued = null">我已保存</button></div>
  <div class="merchant-grid">
    <article v-for="item in merchants" :key="item.id" class="card merchant-card">
      <div class="card-head"><div class="merchant-icon">{{ item.displayName.slice(0, 1) }}</div><span :class="['badge', item.enabled ? 'success' : 'closed']">{{ item.enabled ? 'ENABLED' : 'DISABLED' }}</span></div>
      <h3>{{ item.displayName }}</h3><p class="mono">{{ item.merchantNo }}</p>
      <dl><dt>App ID</dt><dd class="mono">{{ item.appId }}</dd><dt>签名算法</dt><dd><span class="badge pending">{{ item.signingAlgorithm }}</span></dd><dt>App Secret</dt><dd>{{ item.secretMasked }} · v{{ item.credentialVersion }}</dd><dt>回调白名单</dt><dd>{{ item.notifyUrl || '未配置' }}</dd><dt>商户公钥</dt><dd>{{ item.merchantPublicKey ? '已登记（RSA 验签）' : '不适用（HMAC）' }}</dd></dl>
      <div class="card-actions"><button class="secondary" @click="rotate(item)">轮换 Secret</button><button class="danger-link" @click="toggle(item)">{{ item.enabled ? '禁用' : '启用' }}</button></div>
    </article>
    <div v-if="!merchants.length" class="empty card">还没有商户。创建后 App Secret 只会显示一次。</div>
  </div>
  <div v-if="showCreate" class="modal-backdrop"><form class="modal" @submit.prevent="create"><div class="card-head"><div><span class="eyebrow">NEW MERCHANT</span><h2>创建模拟商户</h2></div><button type="button" class="ghost" @click="showCreate = false">✕</button></div><label>显示名称<input v-model="form.displayName" required /></label><label>异步通知地址（服务端白名单）<input v-model="form.notifyUrl" /></label><label>请求签名算法<select v-model="form.signingAlgorithm"><option value="HMAC_SHA256">HMAC-SHA256（入门）</option><option value="RSA_SHA256">RSA-SHA256（生产形态）</option></select></label><label v-if="form.signingAlgorithm === 'RSA_SHA256'">商户 RSA 公钥（X.509 PEM）<textarea v-model="form.merchantPublicKey" rows="6" required placeholder="-----BEGIN PUBLIC KEY-----"></textarea></label><div class="notice">密钥只在创建时显示；回调地址由商户后台配置，不接受交易请求临时覆盖。</div><button class="primary full">签发商户凭据</button></form></div>
</template>
