<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import PaymentLedger from '../PaymentLedger.vue';
import { api } from '../api';
import { enumLabel, formatDate, t, locale } from '../i18n';
import type { Refund } from '../types';

const refunds = ref<Refund[]>([]);
const error = ref('');

async function load() {
  try {
    refunds.value = await api<Refund[]>('/api/admin/refunds');
    error.value = '';
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('refundLoadFailed');
  }
}

onMounted(load);

async function redeliver(item: Refund) {
  try {
    await api(`/api/admin/refunds/${item.channelRefundNo}/notify`, { method: 'POST' });
    error.value = '';
    await load();
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('refundNotifyFailed');
  }
}

const columns = computed<VxeGridProps<Refund>['columns']>(() => [
  { field: 'channelRefundNo', title: t('refundNumber'), minWidth: 154, slots: { default: 'channelRefundNo' } },
  { field: 'merchantRefundNo', title: t('merchantRefundNumber'), minWidth: 150 },
  {
    field: 'channelTransactionNo',
    title: t('originalTransaction'),
    minWidth: 140,
    slots: { default: 'channelTransactionNo' },
  },
  { field: 'channel', title: t('channel'), width: 85, slots: { default: 'channel' } },
  { field: 'amount', title: t('amount'), width: 82, slots: { default: 'amount' } },
  { field: 'reasonCode', title: t('reason'), width: 105, slots: { default: 'reasonCode' } },
  { field: 'status', title: t('status'), width: 100, slots: { default: 'status' } },
  { field: 'createdAt', title: t('time'), minWidth: 140, slots: { default: 'createdAt' } },
  {
    field: 'actions',
    title: locale.value === 'en' ? 'Actions' : '操作',
    width: 115,
    fixed: 'right',
    slots: { default: 'actionsCell' },
  },
]);
</script>

<template>
  <a-alert v-if="error" type="error" show-icon :message="error" />
  <PaymentLedger
    id="payment-refund"
    :title="t('refundTitle')"
    :rows="refunds"
    :columns="columns"
    row-key="channelRefundNo"
    :search-fields="['channelRefundNo', 'merchantRefundNo', 'channelTransactionNo', 'channel']"
    status-field="status"
    :empty-text="t('noRefunds')"
    @refresh="load"
  >
    <template #channelRefundNo="{ row }"
      ><span class="sim-cell mono" :title="row.channelRefundNo">{{ row.channelRefundNo }}</span></template
    >
    <template #channelTransactionNo="{ row }"
      ><span class="sim-cell mono" :title="row.channelTransactionNo">{{ row.channelTransactionNo }}</span></template
    >
    <template #channel="{ row }">{{ enumLabel(row.channel) }}</template>
    <template #amount="{ row }"
      ><span class="sim-amount">¥{{ Number(row.amount).toFixed(2) }}</span></template
    >
    <template #reasonCode="{ row }">{{ enumLabel(row.reasonCode) }}</template>
    <template #status="{ row }"
      ><a-tag
        :title="row.failureReason"
        :color="row.status === 'SUCCEEDED' ? 'success' : row.status === 'FAILED' ? 'error' : 'processing'"
        >{{ enumLabel(row.status) }}
      </a-tag>
    </template>
    <template #createdAt="{ row }"
      ><span class="sim-cell" :title="formatDate(row.createdAt)">{{ formatDate(row.createdAt) }}</span></template
    >
    <template #actionsCell="{ row }"
      ><a-button v-if="row.canRedeliver" size="small" @click="redeliver(row)"
        >{{ t('redeliverNotification') }}
      </a-button></template
    >
  </PaymentLedger>
</template>
