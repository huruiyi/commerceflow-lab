<script setup lang="ts">
import { onMounted, ref } from 'vue';
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
</script>

<template>
  <div class="page-title">
    <h1>{{ t('merchantPayouts') }}</h1>
    <a-button :loading="loading" @click="load">{{ t('refresh') }}</a-button>
  </div>
  <a-alert v-if="error" type="error" show-icon :message="error" />
  <article class="card">
    <vxe-table :data="rows" border stripe show-overflow>
      <vxe-column field="channelPayoutNo" :title="t('payoutNumber')" min-width="190" />
      <vxe-column field="statementId" :title="t('settlementReference')" min-width="190" />
      <vxe-column field="beneficiaryId" :title="t('merchants')" width="110" />
      <vxe-column field="status" :title="t('status')" width="120" />
      <vxe-column field="amount" :title="t('payoutAmount')" width="130">
        <template #default="{ row }">{{ row.currency }} {{ Number(row.amount).toFixed(2) }}</template>
      </vxe-column>
      <vxe-column field="resolvedAt" :title="t('payoutResolvedAt')" min-width="140">
        <template #default="{ row }">{{ row.resolvedAt ? formatDate(row.resolvedAt) : '—' }}</template>
      </vxe-column>
    </vxe-table>
  </article>
</template>
