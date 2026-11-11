<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../api'
import { enumLabel, formatDate, t } from '../i18n'
import type { Refund } from '../types'
import { authState } from '../main'

const refunds = ref<Refund[]>([])
const error = ref('')
async function load() {
  try { refunds.value = await api<Refund[]>('/api/admin/refunds'); error.value = '' }
  catch (reason) { error.value = reason instanceof Error ? reason.message : t('refundLoadFailed') }
}
onMounted(load)
async function redeliver(item: Refund) {
  try { await api(`/api/admin/refunds/${item.channelRefundNo}/notify`, { method: 'POST' }); error.value = ''; await load() }
  catch (reason) { error.value = reason instanceof Error ? reason.message : t('refundNotifyFailed') }
}
</script>

<template>
  <div class="page-title"><div><span class="eyebrow">05B · {{ t('refundLedger') }}</span><h1>{{ t('refundTitle') }}</h1><p>{{ t('refundDescription') }}</p></div><button class="secondary" @click="load">{{ t('refresh') }}</button></div>
  <div v-if="error" class="notice error">{{ error }}</div>
  <article class="card">
    <div v-if="!refunds.length" class="empty">{{ t('noRefunds') }}</div>
    <div v-else class="table-scroll"><table><thead><tr><th>{{ t('refundNumber') }}</th><th>{{ t('originalTransaction') }}</th><th>{{ t('channel') }}</th><th>{{ t('amount') }}</th><th>{{ t('reason') }}</th><th>{{ t('status') }}</th><th>{{ t('time') }}</th><th></th></tr></thead><tbody><tr v-for="item in refunds" :key="item.channelRefundNo"><td class="mono">{{ item.channelRefundNo }}<small>{{ item.merchantRefundNo }}</small></td><td class="mono">{{ item.channelTransactionNo }}</td><td>{{ enumLabel(item.channel) }}</td><td>¥{{ item.amount }}</td><td>{{ enumLabel(item.reasonCode) }}</td><td><span :class="['badge', item.status.toLowerCase()]">{{ enumLabel(item.status) }}</span><small v-if="item.failureReason">{{ item.failureReason }}</small></td><td>{{ formatDate(item.createdAt) }}</td><td><button v-if="authState.session?.authorities.includes('REFUND_OPERATE')" class="secondary" @click="redeliver(item)">{{ t('redeliverNotification') }}</button></td></tr></tbody></table></div>
  </article>
</template>
