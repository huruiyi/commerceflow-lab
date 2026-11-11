<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, signedApi } from '../api'
import { enumLabel, t } from '../i18n'
import type { Transaction } from '../types'

const transactions = ref<Transaction[]>([])
const error = ref('')
const result = ref<Transaction | null>(null)
const form = ref({ appId: '', algorithm: 'HMAC_SHA256' as 'HMAC_SHA256' | 'RSA_SHA256', appSecret: '', privateKey: '', merchantRequestNo: `ORDER-${Date.now()}`, channel: 'ALIPAY', subject: 'Spring learning order', amount: 99.00, currency: 'CNY', faultScenario: 'NONE' })
const load = async () => transactions.value = await api('/api/admin/transactions')
onMounted(load)

async function create() {
  error.value = ''
  const payload = { merchantRequestNo: form.value.merchantRequestNo, channel: form.value.channel, subject: form.value.subject, amount: form.value.amount, currency: form.value.currency, faultScenario: form.value.faultScenario }
  try {
    result.value = await signedApi('/api/channel/transactions', 'POST', JSON.stringify(payload), { appId: form.value.appId, algorithm: form.value.algorithm, secret: form.value.appSecret, privateKey: form.value.privateKey })
    await load()
  } catch (reason) { error.value = reason instanceof Error ? reason.message : t('createFailed') }
}

async function close(item: Transaction) {
  try { await api(`/api/admin/transactions/${item.channelTransactionNo}/close`, { method: 'POST' }); await load() }
  catch (reason) { error.value = reason instanceof Error ? reason.message : t('closeFailed') }
}
</script>

<template>
  <div class="page-title"><div><span class="eyebrow">{{ t('signedApiLab') }} · 05A-3</span><h1>{{ t('transactionTitle') }}</h1><p>{{ t('transactionDescription') }}</p></div><span class="badge success">{{ t('signedRequests') }}</span></div>
  <div v-if="error" class="notice error">{{ error }}</div>
  <div class="content-grid transaction-layout">
    <form class="card" @submit.prevent="create">
      <div class="card-head"><div><span class="eyebrow">{{ t('merchantSdkSimulator') }}</span><h3>{{ t('createSignedTransaction') }}</h3></div><span class="badge pending">05A-3</span></div>
      <label>App ID<input v-model="form.appId" required /></label>
      <label>{{ t('signingAlgorithm') }}<select v-model="form.algorithm"><option value="HMAC_SHA256">HMAC_SHA256</option><option value="RSA_SHA256">RSA_SHA256</option></select></label>
      <label v-if="form.algorithm === 'HMAC_SHA256'">{{ t('appSecret') }}<input v-model="form.appSecret" type="password" required autocomplete="off" /></label>
      <label v-else>{{ t('rsaPrivateKey') }}<textarea v-model="form.privateKey" rows="6" required placeholder="-----BEGIN PRIVATE KEY-----"></textarea></label>
      <label>{{ t('merchantRequestNo') }}<input v-model="form.merchantRequestNo" required /></label>
      <label>{{ t('subject') }}<input v-model="form.subject" required /></label>
      <div class="form-row"><label>{{ t('channel') }}<select v-model="form.channel"><option value="ALIPAY">{{ enumLabel('ALIPAY') }}</option><option value="WECHAT_PAY">{{ enumLabel('WECHAT_PAY') }}</option><option value="UNION_PAY">{{ enumLabel('UNION_PAY') }}</option></select></label><label>{{ t('amount') }}<input v-model.number="form.amount" type="number" min="0.01" step="0.01" /></label></div>
      <label>{{ t('faultScenario') }}<select v-model="form.faultScenario"><option value="NONE">{{ enumLabel('NONE') }}</option><option value="CREATE_FAILURE">{{ enumLabel('CREATE_FAILURE') }}</option><option value="DELAYED_SUCCESS">{{ enumLabel('DELAYED_SUCCESS') }}</option><option value="NOTIFY_LOST">{{ enumLabel('NOTIFY_LOST') }}</option></select></label>
      <button class="primary full">{{ t('sendSigned') }}</button><p class="muted small">{{ t('signatureHint') }}</p>
    </form>
    <article class="card span-2">
      <div class="card-head"><div><span class="eyebrow">{{ t('liveLedger') }}</span><h3>{{ t('channelLedger') }}</h3></div><button class="secondary" @click="load">{{ t('refresh') }}</button></div>
      <div v-if="!transactions.length" class="empty">{{ t('noLedgerTransactions') }}</div>
      <div v-else class="table-scroll"><table><thead><tr><th>{{ t('channelTransactionNo') }}</th><th>{{ t('channelAndSubject') }}</th><th>{{ t('amount') }}</th><th>{{ t('fault') }}</th><th>{{ t('status') }}</th><th></th></tr></thead><tbody><tr v-for="item in transactions" :key="item.channelTransactionNo"><td class="mono">{{ item.channelTransactionNo.slice(0, 18) }}…<small>{{ item.merchantRequestNo }}</small></td><td><b>{{ enumLabel(item.channel) }}</b><small>{{ item.subject }}</small></td><td>¥{{ item.amount }}</td><td>{{ enumLabel(item.faultScenario) }}</td><td><span :class="['badge', item.status.toLowerCase()]">{{ enumLabel(item.status) }}</span></td><td><button v-if="item.status === 'PENDING'" class="danger-link" @click="close(item)">{{ t('close') }}</button></td></tr></tbody></table></div>
    </article>
  </div>
  <section class="card" style="margin-top:18px"><span class="eyebrow">{{ t('securityPipeline') }}</span><h3>{{ t('verificationOrder') }}</h3><div class="stat-grid"><div class="stat"><span>01</span><strong>{{ t('timeWindow') }}</strong><small>{{ t('fiveMinutes') }}</small></div><div class="stat"><span>02</span><strong>{{ t('digest') }}</strong><small>{{ t('detectTampering') }}</small></div><div class="stat"><span>03</span><strong>HMAC / RSA</strong><small>{{ t('constantTime') }}</small></div><div class="stat"><span>04</span><strong>{{ t('redisNonce') }}</strong><small>{{ t('atomicTtl') }}</small></div><div class="stat"><span>05</span><strong>{{ t('mysqlConstraint') }}</strong><small>{{ t('finalReplayProtection') }}</small></div></div></section>
  <div v-if="result" class="result-drawer"><div><span class="eyebrow">{{ t('verifiedResponse') }}</span><h3>{{ enumLabel(result.actionType) }}</h3><p class="mono">{{ result.channelTransactionNo }}</p></div><a class="primary" :href="result.checkoutUrl" target="_blank">{{ t('openCashier') }}</a><button class="ghost" @click="result = null">{{ t('close') }}</button></div>
</template>
