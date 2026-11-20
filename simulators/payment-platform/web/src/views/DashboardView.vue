<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { CircleCheck, Clock3, CreditCard, Store } from '@lucide/vue';
import { api } from '../api';
import { authState } from '../main';
import type { Merchant, Transaction } from '../types';
import { enumLabel, t } from '../i18n';
import PaymentStatistics from './PaymentStatistics.vue';

const transactions = ref<Transaction[]>([]);
const merchants = ref<Merchant[]>([]);
const transactionState = ref<'loading' | 'loaded' | 'error'>();
onMounted(async () => {
  if (authState.session?.authorities.includes('TRANSACTION_READ')) {
    transactionState.value = 'loading';
    try {
      transactions.value = await api('/api/admin/transactions');
      transactionState.value = 'loaded';
    } catch {
      transactionState.value = 'error';
    }
  }
  if (authState.session?.authorities.includes('MERCHANT_MANAGE')) merchants.value = await api('/api/admin/merchants');
});
const successful = computed(() => transactions.value.filter((t) => t.status === 'SUCCESS').length);
const pending = computed(() => transactions.value.filter((t) => t.status === 'PENDING').length);
const metrics = computed(() => [
  { label: 'channelTransactions', value: transactions.value.length, icon: CreditCard, color: 'text-primary' },
  { label: 'successfulTransactions', value: successful.value, icon: CircleCheck, color: 'text-success' },
  { label: 'pendingPayment', value: pending.value, icon: Clock3, color: 'text-warning' },
  {
    label: 'enabledMerchants',
    value: merchants.value.filter((merchant) => merchant.enabled).length,
    icon: Store,
    color: 'text-primary',
  },
]);
</script>

<template>
  <!-- Vben Admin AnalysisOverview layout (MIT). -->
  <section class="grid grid-cols-2 gap-4 lg:grid-cols-4" :aria-label="t('paymentOverview')">
    <article v-for="metric in metrics" :key="metric.label" class="card-box flex min-w-0 flex-col gap-4 p-4 shadow-sm">
      <h2 class="m-0 text-base font-semibold md:text-xl">{{ t(metric.label) }}</h2>
      <div class="flex items-center justify-between gap-2">
        <strong class="text-2xl font-semibold tabular-nums">{{ metric.value.toLocaleString() }}</strong>
        <component :is="metric.icon" class="size-8 shrink-0" :class="metric.color" aria-hidden="true" />
      </div>
    </article>
  </section>
  <PaymentStatistics v-if="transactionState === 'loaded' && transactions.length" :transactions="transactions" />
  <a-card v-else-if="transactionState === 'loaded'" class="mt-5" :title="t('paymentStatistics')">
    <a-empty :description="t('noVisibleTransactions')" />
  </a-card>
  <a-alert v-else-if="transactionState === 'error'" class="mt-5" type="error" :message="t('paymentStatisticsError')" />
  <div class="content-grid mt-5">
    <article class="card span-2">
      <div class="card-head">
        <div>
          <span class="eyebrow">{{ t('recentFlow') }}</span>
          <h3>{{ t('recentTransactions') }}</h3>
        </div>
        <router-link to="/transactions">{{ t('viewAll') }}</router-link>
      </div>
      <vxe-table :data="transactions.slice(0, 5)" stripe show-overflow :empty-text="t('noVisibleTransactions')">
        <vxe-column field="channelTransactionNo" :title="t('transactionNo')" min-width="210">
          <template #default="{ row }"
            ><span class="mono">{{ row.channelTransactionNo.slice(0, 16) }}…</span></template
          >
        </vxe-column>
        <vxe-column field="channel" :title="t('channel')" width="150">
          <template #default="{ row }">{{ enumLabel(row.channel) }}</template>
        </vxe-column>
        <vxe-column field="amount" :title="t('amount')" width="120">
          <template #default="{ row }">¥{{ row.amount }}</template>
        </vxe-column>
        <vxe-column field="status" :title="t('status')" width="130">
          <template #default="{ row }">
            <a-tag :color="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'error' : 'processing'"
              >{{ enumLabel(row.status) }}
            </a-tag>
          </template>
        </vxe-column>
      </vxe-table>
    </article>
    <article class="card">
      <span class="eyebrow">{{ t('securityPosture') }}</span>
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
