<script setup lang="ts">
import {onMounted, ref} from 'vue'
import {api} from '../api'
import {enumLabel, formatDate, t} from '../i18n'
import type {Refund} from '../types'
import {authState} from '../main'

const refunds = ref<Refund[]>([])
const error = ref('')

async function load() {
  try {
    refunds.value = await api<Refund[]>('/api/admin/refunds');
    error.value = ''
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('refundLoadFailed')
  }
}

onMounted(load)

async function redeliver(item: Refund) {
  try {
    await api(`/api/admin/refunds/${item.channelRefundNo}/notify`, {method: 'POST'});
    error.value = '';
    await load()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('refundNotifyFailed')
  }
}
</script>

<template>
  <div class="page-title">
    <div><span class="eyebrow">05B · {{ t('refundLedger') }}</span>
      <h1>{{ t('refundTitle') }}</h1>
      <p>{{ t('refundDescription') }}</p></div>
    <a-button @click="load">{{ t('refresh') }}</a-button>
  </div>
  <a-alert v-if="error" type="error" show-icon :message="error"/>
  <article class="card">
    <vxe-table :data="refunds" border stripe show-overflow :empty-text="t('noRefunds')">
      <vxe-column field="channelRefundNo" :title="t('refundNumber')" width="230">
        <template #default="{ row }"><span class="mono">{{ row.channelRefundNo }}</span><small>{{ row.merchantRefundNo
          }}</small></template>
      </vxe-column>
      <vxe-column field="channelTransactionNo" :title="t('originalTransaction')" width="220">
        <template #default="{ row }"><span class="mono">{{ row.channelTransactionNo }}</span></template>
      </vxe-column>
      <vxe-column field="channel" :title="t('channel')" width="130">
        <template #default="{ row }">{{ enumLabel(row.channel) }}</template>
      </vxe-column>
      <vxe-column field="amount" :title="t('amount')" width="110">
        <template #default="{ row }">¥{{ row.amount }}</template>
      </vxe-column>
      <vxe-column field="reasonCode" :title="t('reason')" width="170">
        <template #default="{ row }">{{ enumLabel(row.reasonCode) }}</template>
      </vxe-column>
      <vxe-column field="status" :title="t('status')" width="150">
        <template #default="{ row }">
          <a-tag :color="row.status === 'SUCCEEDED' ? 'success' : row.status === 'FAILED' ? 'error' : 'processing'">{{
            enumLabel(row.status) }}
          </a-tag>
          <small v-if="row.failureReason">{{ row.failureReason }}</small></template>
      </vxe-column>
      <vxe-column field="createdAt" :title="t('time')" width="190">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </vxe-column>
      <vxe-column width="170" fixed="right">
        <template #default="{ row }">
          <a-button v-if="authState.session?.authorities.includes('REFUND_OPERATE')" size="small"
                    @click="redeliver(row)">{{ t('redeliverNotification') }}
          </a-button>
        </template>
      </vxe-column>
    </vxe-table>
  </article>
</template>
