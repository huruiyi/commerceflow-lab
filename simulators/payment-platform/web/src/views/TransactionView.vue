<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import PaymentLedger from '../PaymentLedger.vue';
import { api, signedApi } from '../api';
import { enumLabel, t, locale, formatDate } from '../i18n';
import type { Transaction } from '../types';

const transactions = ref<Transaction[]>([]);
const error = ref('');
const result = ref<Transaction | null>(null);
const form = ref({
  appId: '',
  algorithm: 'HMAC_SHA256' as 'HMAC_SHA256' | 'RSA_SHA256',
  appSecret: '',
  privateKey: '',
  merchantRequestNo: `ORDER-${Date.now()}`,
  channel: 'ALIPAY',
  subject: 'Spring learning order',
  amount: 99.0,
  currency: 'CNY',
  faultScenario: 'NONE',
});
const load = async () => (transactions.value = await api('/api/admin/transactions'));
onMounted(load);

async function create() {
  error.value = '';
  const payload = {
    merchantRequestNo: form.value.merchantRequestNo,
    channel: form.value.channel,
    subject: form.value.subject,
    amount: form.value.amount,
    currency: form.value.currency,
    faultScenario: form.value.faultScenario,
  };
  try {
    result.value = await signedApi('/api/channel/transactions', 'POST', JSON.stringify(payload), {
      appId: form.value.appId,
      algorithm: form.value.algorithm,
      secret: form.value.appSecret,
      privateKey: form.value.privateKey,
    });
    await load();
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('createFailed');
  }
}

async function close(item: Transaction) {
  try {
    await api(`/api/admin/transactions/${item.channelTransactionNo}/close`, { method: 'POST' });
    await load();
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('closeFailed');
  }
}

const showCreate = ref(false);

const columns = computed<VxeGridProps<Transaction>['columns']>(() => [
  {
    field: 'channelTransactionNo',
    title: t('channelTransactionNo'),
    minWidth: 150,
    slots: { default: 'channelTransactionNo' },
  },
  { field: 'merchantRequestNo', title: t('merchantRequestNo'), minWidth: 145 },
  { field: 'channel', title: t('channel'), width: 88, slots: { default: 'channel' } },
  { field: 'subject', title: t('subject'), minWidth: 140 },
  { field: 'amount', title: t('amount'), width: 84, slots: { default: 'amount' } },
  { field: 'faultScenario', title: t('fault'), width: 104, slots: { default: 'faultScenario' } },
  { field: 'status', title: t('status'), width: 98, slots: { default: 'status' } },
  { field: 'createdAt', title: t('time'), minWidth: 140, slots: { default: 'createdAt' } },
  {
    field: 'actions',
    title: locale.value === 'en' ? 'Actions' : '操作',
    width: 100,
    fixed: 'right',
    slots: { default: 'actionsCell' },
  },
]);
</script>

<template>
  <a-alert v-if="error" type="error" show-icon :message="error" />
  <form v-if="showCreate" class="card payment-create" @submit.prevent="create">
    <div class="card-head">
      <div>
        <h3>{{ t('createSignedTransaction') }}</h3>
      </div>
      <button type="button" class="sim-button" @click="showCreate = false">{{ t('close') }}</button>
    </div>
    <label>App ID<input v-model="form.appId" required /></label>
    <label
      >{{ t('signingAlgorithm')
      }}<select v-model="form.algorithm">
        <option value="HMAC_SHA256">HMAC_SHA256</option>
        <option value="RSA_SHA256">RSA_SHA256</option>
      </select></label
    >
    <label v-if="form.algorithm === 'HMAC_SHA256'"
      >{{ t('appSecret') }}<input v-model="form.appSecret" type="password" required autocomplete="off"
    /></label>
    <label v-else
      >{{ t('rsaPrivateKey')
      }}<textarea v-model="form.privateKey" rows="6" required placeholder="-----BEGIN PRIVATE KEY-----"></textarea>
    </label>
    <label>{{ t('merchantRequestNo') }}<input v-model="form.merchantRequestNo" required /></label>
    <label>{{ t('subject') }}<input v-model="form.subject" required /></label>
    <div class="form-row">
      <label
        >{{ t('channel')
        }}<select v-model="form.channel">
          <option value="ALIPAY">{{ enumLabel('ALIPAY') }}</option>
          <option value="WECHAT_PAY">{{ enumLabel('WECHAT_PAY') }}</option>
          <option value="UNION_PAY">{{ enumLabel('UNION_PAY') }}</option>
        </select></label
      ><label>{{ t('amount') }}<input v-model.number="form.amount" type="number" min="0.01" step="0.01" /></label>
    </div>
    <label
      >{{ t('faultScenario')
      }}<select v-model="form.faultScenario">
        <option value="NONE">{{ enumLabel('NONE') }}</option>
        <option value="CREATE_FAILURE">{{ enumLabel('CREATE_FAILURE') }}</option>
        <option value="DELAYED_SUCCESS">{{ enumLabel('DELAYED_SUCCESS') }}</option>
        <option value="NOTIFY_LOST">{{ enumLabel('NOTIFY_LOST') }}</option>
      </select></label
    >
    <a-button type="primary" html-type="submit" block>{{ t('sendSigned') }}</a-button>
    <p class="muted small">{{ t('signatureHint') }}</p>
  </form>
  <PaymentLedger
    id="payment-transaction"
    :title="t('channelLedger')"
    :rows="transactions"
    :columns="columns"
    row-key="channelTransactionNo"
    :search-fields="['channelTransactionNo', 'merchantRequestNo', 'subject', 'channel']"
    status-field="status"
    :empty-text="t('noLedgerTransactions')"
    @refresh="load"
  >
    <template #actions
      ><button type="button" class="sim-button primary" @click="showCreate = !showCreate">
        {{ t('createSignedTransaction') }}
      </button></template
    >
    <template #channelTransactionNo="{ row }">
      <span class="sim-cell mono" :title="row.channelTransactionNo">{{ row.channelTransactionNo }}</span>
    </template>
    <template #channel="{ row }">{{ enumLabel(row.channel) }}</template>
    <template #amount="{ row }"
      ><span class="sim-amount">¥{{ Number(row.amount).toFixed(2) }}</span></template
    >
    <template #createdAt="{ row }"
      ><span class="sim-cell" :title="formatDate(row.createdAt)">{{ formatDate(row.createdAt) }}</span></template
    >
    <template #faultScenario="{ row }">{{ enumLabel(row.faultScenario) }}</template>
    <template #status="{ row }"
      ><a-tag :color="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'error' : 'processing'"
        >{{ enumLabel(row.status) }}
      </a-tag></template
    >
    <template #actionsCell="{ row }"
      ><a-button v-if="row.status === 'PENDING'" danger type="link" @click="close(row)">{{
        t('close')
      }}</a-button></template
    >
  </PaymentLedger>
  <div v-if="result" class="result-drawer">
    <div>
      <span class="eyebrow">{{ t('verifiedResponse') }}</span>
      <h3>{{ enumLabel(result.actionType) }}</h3>
      <p class="mono">{{ result.channelTransactionNo }}</p>
    </div>
    <a class="primary" :href="result.checkoutUrl" target="_blank">{{ t('openCashier') }}</a>
    <button class="ghost" @click="result = null">{{ t('close') }}</button>
  </div>
</template>
