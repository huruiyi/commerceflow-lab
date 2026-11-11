<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { authState } from '../main'
import type { Merchant, Transaction } from '../types'
import { enumLabel, t } from '../i18n'

const transactions = ref<Transaction[]>([])
const merchants = ref<Merchant[]>([])
onMounted(async () => {
  if (authState.session?.authorities.includes('TRANSACTION_READ')) transactions.value = await api('/api/admin/transactions')
  if (authState.session?.authorities.includes('MERCHANT_MANAGE')) merchants.value = await api('/api/admin/merchants')
})
const successful = computed(() => transactions.value.filter(t => t.status === 'SUCCESS').length)
const pending = computed(() => transactions.value.filter(t => t.status === 'PENDING').length)
</script>

<template>
  <div class="page-title"><div><span class="eyebrow">{{ t('controlCenter') }}</span><h1>{{ t('greeting', { name: authState.session?.displayName || '' }) }}</h1><p>{{ t('dashboardDescription') }}</p></div><span class="status-pill"><i></i>{{ t('platformRunning') }}</span></div>
  <div class="metric-grid">
    <article class="metric accent"><span>{{ t('channelTransactions') }}</span><b>{{ transactions.length }}</b><small>{{ t('independentDatabase') }}</small></article>
    <article class="metric"><span>{{ t('successfulTransactions') }}</span><b>{{ successful }}</b><small>{{ t('simulatedTerminal') }}</small></article>
    <article class="metric"><span>{{ t('pendingPayment') }}</span><b>{{ pending }}</b><small>{{ t('validFor15Minutes') }}</small></article>
    <article class="metric"><span>{{ t('enabledMerchants') }}</span><b>{{ merchants.filter(m => m.enabled).length }}</b><small>App ID / Secret</small></article>
  </div>
  <div class="content-grid">
    <article class="card span-2"><div class="card-head"><div><span class="eyebrow">{{ t('recentFlow') }}</span><h3>{{ t('recentTransactions') }}</h3></div><router-link to="/transactions">{{ t('viewAll') }}</router-link></div>
      <div v-if="!transactions.length" class="empty">{{ t('noVisibleTransactions') }}</div>
      <table v-else><thead><tr><th>{{ t('transactionNo') }}</th><th>{{ t('channel') }}</th><th>{{ t('amount') }}</th><th>{{ t('status') }}</th></tr></thead><tbody><tr v-for="item in transactions.slice(0, 5)" :key="item.channelTransactionNo"><td class="mono">{{ item.channelTransactionNo.slice(0, 16) }}…</td><td>{{ enumLabel(item.channel) }}</td><td>¥{{ item.amount }}</td><td><span :class="['badge', item.status.toLowerCase()]">{{ enumLabel(item.status) }}</span></td></tr></tbody></table>
    </article>
    <article class="card"><span class="eyebrow">{{ t('securityPosture') }}</span><h3>{{ t('currentSecurityBoundary') }}</h3><ul class="check-list"><li>{{ t('sessionIsolation') }}</li><li>{{ t('secretOnce') }}</li><li>{{ t('stepUpAuth') }}</li><li>{{ t('dualAuthorization') }}</li><li class="future">{{ t('signatureLab') }}</li></ul></article>
  </div>
</template>
