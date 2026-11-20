<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import PaymentLedger from '../PaymentLedger.vue';
import { api } from '../api';
import { formatDate, t } from '../i18n';

interface Payout {
  channelPayoutNo: string;
  statementId: string;
  beneficiaryId: number;
  status: string;
  currency: string;
  amount: number;
  resolvedAt?: string;
}

const rows = ref<Payout[]>([]);
const error = ref('');
const loading = ref(false);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    rows.value = await api<Payout[]>('/api/admin/payouts');
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('payoutLoadFailed');
  } finally {
    loading.value = false;
  }
}

onMounted(load);

const columns = computed<VxeGridProps<Payout>['columns']>(() => [
  { field: 'channelPayoutNo', title: t('payoutNumber'), minWidth: 190 },
  { field: 'statementId', title: t('settlementReference'), minWidth: 190 },
  { field: 'beneficiaryId', title: t('merchants'), width: 110 },
  { field: 'status', title: t('status'), width: 120 },
  { field: 'amount', title: t('payoutAmount'), width: 130, slots: { default: 'amount' } },
  { field: 'resolvedAt', title: t('payoutResolvedAt'), minWidth: 140, slots: { default: 'resolvedAt' } },
]);
</script>

<template>
  <a-alert v-if="error" type="error" show-icon :message="error" />
  <PaymentLedger
    id="payment-payout"
    :title="t('merchantPayouts')"
    :rows="rows"
    :columns="columns"
    row-key="channelPayoutNo"
    :search-fields="['channelPayoutNo', 'statementId', 'beneficiaryId']"
    status-field="status"
    :loading="loading"
    @refresh="load"
  >
    <template #amount="{ row }">{{ row.currency }} {{ Number(row.amount).toFixed(2) }}</template>
    <template #resolvedAt="{ row }">{{ row.resolvedAt ? formatDate(row.resolvedAt) : '—' }}</template>
  </PaymentLedger>
</template>
