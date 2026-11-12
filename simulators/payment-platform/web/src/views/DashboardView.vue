<script setup lang="ts">
import {computed, onMounted, ref} from 'vue'
import {api} from '../api'
import {authState} from '../main'
import type {Merchant, Transaction} from '../types'
import {enumLabel, t} from '../i18n'

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
  <div class="page-title">
    <div><span class="eyebrow">{{ t('controlCenter') }}</span>
      <h1>{{ t('greeting', { name: authState.session?.displayName || '' }) }}</h1>
      <p>{{ t('dashboardDescription') }}</p></div>
    <span class="status-pill"><i></i>{{ t('platformRunning') }}</span></div>
  <div class="metric-grid">
    <article class="metric accent"><span>{{ t('channelTransactions') }}</span><b>{{ transactions.length }}</b><small>{{
      t('independentDatabase') }}</small></article>
    <article class="metric"><span>{{ t('successfulTransactions') }}</span><b>{{ successful }}</b><small>{{
      t('simulatedTerminal') }}</small></article>
    <article class="metric"><span>{{ t('pendingPayment') }}</span><b>{{ pending }}</b><small>{{ t('validFor15Minutes')
      }}</small></article>
    <article class="metric"><span>{{ t('enabledMerchants') }}</span><b>{{ merchants.filter(m => m.enabled).length }}</b><small>App
      ID / Secret</small></article>
  </div>
  <div class="content-grid">
    <article class="card span-2">
      <div class="card-head">
        <div><span class="eyebrow">{{ t('recentFlow') }}</span>
          <h3>{{ t('recentTransactions') }}</h3></div>
        <router-link to="/transactions">{{ t('viewAll') }}</router-link>
      </div>
      <vxe-table :data="transactions.slice(0, 5)" stripe show-overflow :empty-text="t('noVisibleTransactions')">
        <vxe-column field="channelTransactionNo" :title="t('transactionNo')" min-width="210">
          <template #default="{ row }"><span class="mono">{{ row.channelTransactionNo.slice(0, 16) }}…</span></template>
        </vxe-column>
        <vxe-column field="channel" :title="t('channel')" width="150">
          <template #default="{ row }">{{ enumLabel(row.channel) }}</template>
        </vxe-column>
        <vxe-column field="amount" :title="t('amount')" width="120">
          <template #default="{ row }">¥{{ row.amount }}</template>
        </vxe-column>
        <vxe-column field="status" :title="t('status')" width="130">
          <template #default="{ row }">
            <a-tag :color="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'error' : 'processing'">{{
              enumLabel(row.status) }}
            </a-tag>
          </template>
        </vxe-column>
      </vxe-table>
    </article>
    <article class="card"><span class="eyebrow">{{ t('securityPosture') }}</span>
      <h3>{{ t('currentSecurityBoundary') }}</h3>
      <ul class="check-list">
        <li>{{ t('sessionIsolation') }}</li>
        <li>{{ t('secretOnce') }}</li>
        <li>{{ t('stepUpAuth') }}</li>
        <li>{{ t('dualAuthorization') }}</li>
        <li class="future">{{ t('signatureLab') }}</li>
      </ul>
    </article>
  </div>
</template>
