<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {useRoute} from 'vue-router'
import QRCode from 'qrcode'
import {api} from '../api'
import {enumLabel, formatDate, languageLabel, switchLanguage, t} from '../i18n'
import type {Transaction} from '../types'

const route = useRoute()
const tx = ref<Transaction | null>(null)
const error = ref('')
const qr = ref('')
const token = String(route.params.token)
const channelName = computed(() => ({
  ALIPAY: t('alipayCashier'),
  WECHAT_PAY: t('wechatCashier'),
  UNION_PAY: t('unionPayCashier'),
}[tx.value?.channel || ''] || t('cashier')))

onMounted(async () => {
  try {
    const loaded = await api<Transaction>(`/api/checkout/${token}`)
    tx.value = loaded
    if (loaded.qrPayload) qr.value = await QRCode.toDataURL(loaded.qrPayload, {width: 180, margin: 1})
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('transactionMissing')
  }
})

async function complete(outcome: string) {
  try {
    tx.value = await api(`/api/checkout/${token}/complete?outcome=${outcome}`, {method: 'POST'})
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('processingFailed')
  }
}
</script>

<template>
  <div class="cashier-page">
    <header class="cashier-brand">
      <span class="brand-mark">C</span><strong>CommerceFlow Pay</strong>
      <span>{{ t('localOnly') }}</span>
      <button class="language-toggle dark" type="button" @click="switchLanguage">{{ languageLabel }}</button>
    </header>
    <article v-if="tx" class="cashier-card">
      <div class="cashier-heading"><span class="eyebrow">{{ enumLabel(tx.channel) }}</span>
        <h1>{{ channelName }}</h1></div>
      <div class="amount"><small>{{ t('amountDue') }}</small><b>¥{{ tx.amount }}</b><span>{{ tx.currency }}</span></div>
      <div :class="['cashier-content', { 'without-qr': !qr }]">
        <div v-if="qr" class="qr-panel"><img :src="qr" class="qr" :alt="t('qrAlt')"/></div>
        <div class="cashier-details">
          <dl>
            <dt>{{ t('order') }}</dt>
            <dd>{{ tx.subject }}</dd>
            <dt>{{ t('channelTransactionNo') }}</dt>
            <dd class="mono">{{ tx.channelTransactionNo }}</dd>
            <dt>{{ t('status') }}</dt>
            <dd><span :class="['badge', tx.status.toLowerCase()]">{{ enumLabel(tx.status) }}</span></dd>
            <dt>{{ t('expiresAt') }}</dt>
            <dd>{{ formatDate(tx.expiresAt) }}</dd>
          </dl>
          <div v-if="tx.status === 'PENDING'" class="cashier-actions">
            <button class="primary" @click="complete('SUCCESS')">{{ t('paySuccess') }}</button>
            <button class="secondary" @click="complete('FAILED')">{{ t('regularFailure') }}</button>
            <button class="secondary" @click="complete('INSUFFICIENT_FUNDS')">{{ t('insufficientFunds') }}</button>
            <button class="secondary" @click="complete('CANCELLED')">{{ t('userCancelled') }}</button>
            <button class="secondary" @click="complete('TIMEOUT')">{{ t('paymentTimeout') }}</button>
          </div>
          <div v-else class="notice success">{{ t('terminalNotice', { status: enumLabel(tx.status) }) }}</div>
        </div>
      </div>
      <p class="cashier-disclaimer">{{ t('cashierDisclaimer') }}</p>
    </article>
    <article v-else class="cashier-card cashier-loading">
      <div v-if="error" class="notice error">{{ error }}</div>
      <p v-else>{{ t('loadingTransaction') }}</p></article>
  </div>
</template>
