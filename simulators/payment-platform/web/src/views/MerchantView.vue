<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import PaymentLedger from '../PaymentLedger.vue';
import { api, takeStepUpToken } from '../api';
import type { IssuedCredential, Merchant } from '../types';
import { enumLabel, t } from '../i18n';

const merchants = ref<Merchant[]>([]);
const selectedId = ref<number>();
const selected = computed(() => merchants.value.find(({ id }) => id === selectedId.value));
const rows = computed(() =>
  merchants.value.map((merchant) => ({
    ...merchant,
    merchantStatus: merchant.enabled ? 'ENABLED' : 'DISABLED',
  })),
);
const columns = computed<VxeGridProps<(typeof rows.value)[number]>['columns']>(() => [
  { field: 'displayName', title: t('displayName'), minWidth: 170 },
  { field: 'merchantNo', title: t('merchants'), minWidth: 155 },
  { field: 'appId', title: 'App ID', minWidth: 220 },
  { field: 'signingAlgorithm', title: t('signingAlgorithm'), minWidth: 135 },
  { field: 'merchantStatus', title: t('status'), width: 110, slots: { default: 'status' } },
  { field: 'actions', title: t('action'), width: 120, fixed: 'right', slots: { default: 'operation' } },
]);
const showCreate = ref(false);
const issued = ref<IssuedCredential | null>(null);
const error = ref('');
const form = ref({
  displayName: 'CommerceFlow Lab',
  notifyUrl: 'http://commerce-service:8080/api/payment/notifications',
  signingAlgorithm: 'HMAC_SHA256',
  merchantPublicKey: '',
});
const load = async () => (merchants.value = await api('/api/admin/merchants'));
onMounted(load);

async function create() {
  error.value = '';
  try {
    issued.value = await api('/api/admin/merchants', { method: 'POST', body: JSON.stringify(form.value) });
    showCreate.value = false;
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('createFailed');
  }
}

async function rotate(item: Merchant) {
  const token = takeStepUpToken();
  if (!token) {
    error.value = t('stepUpFirst');
    return;
  }
  try {
    issued.value = await api(`/api/admin/merchants/${item.id}/rotate-secret`, {
      method: 'POST',
      headers: { 'X-Step-Up-Token': token },
    });
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('rotateFailed');
  }
}

async function toggle(item: Merchant) {
  const token = takeStepUpToken();
  if (!token) {
    error.value = t('stepUpFirst');
    return;
  }
  try {
    await api(`/api/admin/merchants/${item.id}/enabled/${!item.enabled}`, {
      method: 'POST',
      headers: { 'X-Step-Up-Token': token },
    });
    await load();
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('operationFailed');
  }
}

async function downloadPublicKey() {
  const text = await api<string>('/api/admin/merchants/platform-public-key');
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = 'commerceflow-pay-platform-public.pem';
  anchor.click();
  URL.revokeObjectURL(url);
}

async function copySecret() {
  if (issued.value) await window.navigator.clipboard.writeText(issued.value.appSecret);
}
</script>

<template>
  <div v-if="error" class="notice error">{{ error }}</div>
  <div v-if="issued" class="secret-panel">
    <div>
      <span class="eyebrow">{{ t('oneTimeCredential') }}</span>
      <h3>{{ t('copySecretNow') }}</h3>
      <p>{{ issued.warning }}</p>
    </div>
    <div class="secret-value">
      <code>{{ issued.appSecret }}</code>
      <button @click="copySecret">{{ t('copy') }}</button>
    </div>
    <button class="ghost" @click="issued = null">{{ t('saved') }}</button>
  </div>
  <PaymentLedger
    id="payment-merchants"
    :title="t('merchantTitle')"
    :rows="rows"
    :columns="columns"
    row-key="id"
    :search-fields="['displayName', 'merchantNo', 'appId', 'notifyUrl']"
    status-field="merchantStatus"
    :empty-text="t('noMerchants')"
    @refresh="load"
  >
    <template #actions>
      <button class="sim-button" @click="downloadPublicKey">{{ t('downloadPublicKey') }}</button>
      <button class="sim-button primary" @click="showCreate = true">{{ t('createMerchant') }}</button>
    </template>
    <template #status="{ row }">{{ enumLabel(row.merchantStatus) }}</template>
    <template #operation="{ row }">
      <button class="sim-text-button" @click="selectedId = row.id">{{ t('details') }}</button>
    </template>
  </PaymentLedger>
  <article v-if="selected" class="card merchant-card">
    <div class="card-head">
      <div class="merchant-icon">{{ selected.displayName.slice(0, 1) }}</div>
      <span :class="['badge', selected.enabled ? 'success' : 'closed']">{{
        enumLabel(selected.enabled ? 'ENABLED' : 'DISABLED')
      }}</span>
      <button class="sim-button" @click="selectedId = undefined">{{ t('close') }}</button>
    </div>
    <h3>{{ selected.displayName }}</h3>
    <p class="mono">{{ selected.merchantNo }}</p>
    <dl>
      <dt>App ID</dt>
      <dd class="mono">{{ selected.appId }}</dd>
      <dt>{{ t('signingAlgorithm') }}</dt>
      <dd>
        <span class="badge pending">{{ selected.signingAlgorithm }}</span>
      </dd>
      <dt>App Secret</dt>
      <dd>{{ selected.secretMasked }} · v{{ selected.credentialVersion }}</dd>
      <dt>{{ t('callbackAllowlist') }}</dt>
      <dd>{{ selected.notifyUrl || t('notConfigured') }}</dd>
      <dt>{{ t('merchantPublicKey') }}</dt>
      <dd>{{ selected.merchantPublicKey ? t('rsaRegistered') : t('hmacNotApplicable') }}</dd>
    </dl>
    <div class="card-actions">
      <button class="secondary" @click="rotate(selected)">{{ t('rotateSecret') }}</button>
      <button class="danger-link" @click="toggle(selected)">{{ selected.enabled ? t('disable') : t('enable') }}</button>
    </div>
  </article>
  <div v-if="showCreate" class="modal-backdrop">
    <form class="modal" @submit.prevent="create">
      <div class="card-head">
        <div>
          <span class="eyebrow">{{ t('newMerchant') }}</span>
          <h2>{{ t('createSimulatedMerchant') }}</h2>
        </div>
        <button type="button" class="ghost" @click="showCreate = false">✕</button>
      </div>
      <label>{{ t('displayName') }}<input v-model="form.displayName" required /></label
      ><label>{{ t('notifyUrl') }}<input v-model="form.notifyUrl" /></label
      ><label
        >{{ t('requestSigningAlgorithm')
        }}<select v-model="form.signingAlgorithm">
          <option value="HMAC_SHA256">HMAC-SHA256</option>
          <option value="RSA_SHA256">RSA-SHA256</option>
        </select></label
      ><label v-if="form.signingAlgorithm === 'RSA_SHA256'"
        >{{ t('merchantRsaPublicKey')
        }}<textarea
          v-model="form.merchantPublicKey"
          rows="6"
          required
          placeholder="-----BEGIN PUBLIC KEY-----"
        ></textarea>
      </label>
      <div class="notice">{{ t('credentialNotice') }}</div>
      <button class="primary full">{{ t('issueCredential') }}</button>
    </form>
  </div>
</template>
