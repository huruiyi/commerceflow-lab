<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Modal } from 'ant-design-vue';
import type { VxeGridProps } from 'vxe-table';
import PaymentLedger from '../PaymentLedger.vue';
import { api, takeStepUpToken } from '../api';
import type { IssuedCredential, Merchant } from '../types';
import { enumLabel, t } from '../i18n';

const merchants = ref<Merchant[]>([]);
const selectedId = ref<number>();
const selected = computed(() => merchants.value.find(({ id }) => id === selectedId.value));
const drawer = ref<'create' | 'edit' | 'detail'>();
const drawerTitle = computed(() =>
  t(
    {
      create: 'createMerchant',
      edit: 'editMerchant',
      detail: 'merchantDetails',
    }[drawer.value ?? 'detail'],
  ),
);
const rows = computed(() =>
  merchants.value.map((merchant) => ({
    ...merchant,
    merchantStatus: merchant.enabled ? 'ENABLED' : 'DISABLED',
  })),
);
const columns = computed<VxeGridProps<(typeof rows.value)[number]>['columns']>(() => [
  { field: 'displayName', title: t('displayName'), minWidth: 175, showOverflow: 'title' },
  { field: 'merchantNo', title: t('merchantNo'), minWidth: 130 },
  { field: 'appId', title: 'App ID', minWidth: 185 },
  { field: 'signingAlgorithm', title: t('signingAlgorithm'), minWidth: 135 },
  { field: 'remark', title: t('merchantRemark'), minWidth: 220, align: 'left', showOverflow: 'title' },
  { field: 'merchantStatus', title: t('status'), width: 112, slots: { default: 'status' } },
  { field: 'actions', title: t('accountActions'), width: 132, fixed: 'right', slots: { default: 'operation' } },
]);
const issued = ref<IssuedCredential | null>(null);
const loading = ref(false);
const saving = ref(false);
const statusChanging = ref<number>();
const message = ref('');
const error = ref('');
const drawerError = ref('');

function newMerchant() {
  return {
    displayName: '',
    remark: '',
    notifyUrl: 'http://commerce-service:8080/api/payment/notifications',
    signingAlgorithm: 'HMAC_SHA256',
    merchantPublicKey: '',
  };
}

const form = ref(newMerchant());

async function load() {
  loading.value = true;
  error.value = '';
  try {
    merchants.value = await api('/api/admin/merchants');
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('operationFailed');
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function openDrawer(mode: 'create' | 'edit' | 'detail', item?: Merchant) {
  selectedId.value = item?.id;
  drawerError.value = '';
  if (mode !== 'detail') {
    form.value = { ...newMerchant(), displayName: item?.displayName ?? '', remark: item?.remark ?? '' };
  }
  drawer.value = mode;
}

function replaceMerchant(updated: Merchant) {
  merchants.value = merchants.value.map((merchant) => (merchant.id === updated.id ? updated : merchant));
}

async function save() {
  if (saving.value) return;
  const editing = drawer.value === 'edit';
  const id = selectedId.value;
  if (editing && id === undefined) return;
  drawerError.value = '';
  message.value = '';
  saving.value = true;
  try {
    if (editing) {
      const { displayName, remark } = form.value;
      const updated = await api<Merchant>(`/api/admin/merchants/${id}`, {
        method: 'PUT',
        body: JSON.stringify({ displayName, remark }),
      });
      replaceMerchant(updated);
      message.value = t('merchantUpdated');
    } else {
      const created = await api<IssuedCredential>('/api/admin/merchants', {
        method: 'POST',
        body: JSON.stringify(form.value),
      });
      issued.value = created;
      merchants.value = [created.merchant, ...merchants.value];
    }
    drawer.value = undefined;
  } catch (e) {
    drawerError.value = e instanceof Error ? e.message : t(editing ? 'operationFailed' : 'createFailed');
  } finally {
    saving.value = false;
  }
}

async function rotate(item: Merchant) {
  const token = takeStepUpToken();
  if (!token) {
    drawerError.value = t('stepUpFirst');
    return;
  }
  saving.value = true;
  drawerError.value = '';
  try {
    const rotated = await api<IssuedCredential>(`/api/admin/merchants/${item.id}/rotate-secret`, {
      method: 'POST',
      headers: { 'X-Step-Up-Token': token },
    });
    issued.value = rotated;
    replaceMerchant(rotated.merchant);
    drawer.value = undefined;
  } catch (e) {
    drawerError.value = e instanceof Error ? e.message : t('rotateFailed');
  } finally {
    saving.value = false;
  }
}

function toggle(item: Merchant, enabled: boolean) {
  const action = t(enabled ? 'enable' : 'disable');
  Modal.confirm({
    title: t('merchantStatusPrompt', { action, name: item.displayName }),
    content: enabled ? undefined : t('merchantDisabledHint'),
    okText: action,
    okType: enabled ? 'primary' : 'danger',
    cancelText: t('cancelAction'),
    async onOk() {
      const token = takeStepUpToken();
      if (!token) {
        error.value = t('stepUpFirst');
        return;
      }
      error.value = '';
      statusChanging.value = item.id;
      try {
        const updated = await api<Merchant>(`/api/admin/merchants/${item.id}/enabled/${enabled}`, {
          method: 'POST',
          headers: { 'X-Step-Up-Token': token },
        });
        replaceMerchant(updated);
      } catch (e) {
        error.value = e instanceof Error ? e.message : t('operationFailed');
      } finally {
        statusChanging.value = undefined;
      }
    },
  });
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
  <a-alert v-if="message" class="mb-3" type="success" show-icon closable :message="message" @close="message = ''" />
  <a-alert v-if="error" class="mb-3" type="error" show-icon closable :message="error" @close="error = ''" />
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
    :search-fields="['displayName', 'merchantNo', 'appId', 'notifyUrl', 'remark']"
    status-field="merchantStatus"
    :empty-text="t('noMerchants')"
    :loading="loading"
    @refresh="load"
  >
    <template #actions>
      <a-button @click="downloadPublicKey">{{ t('downloadPublicKey') }}</a-button>
      <a-button type="primary" @click="openDrawer('create')">{{ t('createMerchant') }}</a-button>
    </template>
    <template #status="{ row }">
      <a-switch
        :checked="row.enabled"
        :loading="statusChanging === row.id"
        :disabled="statusChanging !== undefined"
        :checked-children="enumLabel('ENABLED')"
        :un-checked-children="enumLabel('DISABLED')"
        :aria-label="t('status') + ' · ' + row.displayName"
        @change="toggle(row, Boolean($event))"
      />
    </template>
    <template #operation="{ row }">
      <a-button type="link" size="small" @click="openDrawer('detail', row)">{{ t('details') }}</a-button>
      <a-button type="link" size="small" @click="openDrawer('edit', row)">{{ t('editAccountAction') }}</a-button>
    </template>
  </PaymentLedger>

  <a-drawer
    :open="Boolean(drawer)"
    :title="drawerTitle"
    width="min(100%, 520px)"
    :closable="!saving"
    :mask-closable="!saving"
    :keyboard="!saving"
    @close="drawer = undefined"
  >
    <a-alert v-if="drawerError" class="mb-3" type="error" show-icon :message="drawerError" />
    <a-form
      v-if="drawer === 'create' || drawer === 'edit'"
      id="payment-merchant-form"
      :model="form"
      layout="horizontal"
      :label-col="{ flex: '9rem' }"
      :wrapper-col="{ flex: '1' }"
      @finish="save"
    >
      <a-form-item
        name="displayName"
        :label="t('displayName')"
        :rules="[{ required: true, whitespace: true, max: 100 }]"
      >
        <a-input v-model:value="form.displayName" :maxlength="100" />
      </a-form-item>
      <a-form-item name="remark" :label="t('merchantRemark')">
        <a-textarea v-model:value="form.remark" :maxlength="1000" :rows="5" :placeholder="t('merchantRemarkHint')" />
      </a-form-item>
      <template v-if="drawer === 'create'">
        <a-form-item name="notifyUrl" :label="t('notifyUrl')">
          <a-input v-model:value="form.notifyUrl" :maxlength="500" />
        </a-form-item>
        <a-form-item name="signingAlgorithm" :label="t('requestSigningAlgorithm')">
          <a-select v-model:value="form.signingAlgorithm">
            <a-select-option value="HMAC_SHA256">HMAC-SHA256</a-select-option>
            <a-select-option value="RSA_SHA256">RSA-SHA256</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item
          v-if="form.signingAlgorithm === 'RSA_SHA256'"
          name="merchantPublicKey"
          :label="t('merchantRsaPublicKey')"
          :rules="[{ required: true, whitespace: true }]"
        >
          <a-textarea
            v-model:value="form.merchantPublicKey"
            :rows="6"
            :maxlength="8000"
            placeholder="-----BEGIN PUBLIC KEY-----"
          />
        </a-form-item>
        <a-alert type="info" :message="t('credentialNotice')" />
      </template>
    </a-form>

    <a-descriptions
      v-else-if="selected"
      :column="1"
      :label-style="{ width: '32%', textAlign: 'left' }"
      size="small"
      bordered
    >
      <a-descriptions-item :label="t('displayName')">{{ selected.displayName }}</a-descriptions-item>
      <a-descriptions-item :label="t('merchantNo')">{{ selected.merchantNo }}</a-descriptions-item>
      <a-descriptions-item label="App ID">{{ selected.appId }}</a-descriptions-item>
      <a-descriptions-item :label="t('signingAlgorithm')">{{ selected.signingAlgorithm }}</a-descriptions-item>
      <a-descriptions-item label="App Secret"
        >{{ selected.secretMasked }} · v{{ selected.credentialVersion }}</a-descriptions-item
      >
      <a-descriptions-item :label="t('callbackAllowlist')">{{
        selected.notifyUrl || t('notConfigured')
      }}</a-descriptions-item>
      <a-descriptions-item :label="t('merchantPublicKey')">
        {{ selected.merchantPublicKey ? t('rsaRegistered') : t('hmacNotApplicable') }}
      </a-descriptions-item>
      <a-descriptions-item :label="t('status')">{{
        enumLabel(selected.enabled ? 'ENABLED' : 'DISABLED')
      }}</a-descriptions-item>
      <a-descriptions-item :label="t('merchantRemark')">
        <span style="white-space: pre-wrap; overflow-wrap: anywhere">{{ selected.remark || '—' }}</span>
      </a-descriptions-item>
    </a-descriptions>

    <template #footer>
      <div class="flex justify-end">
        <a-space v-if="drawer === 'detail' && selected">
          <a-button :disabled="saving" @click="drawer = undefined">{{ t('close') }}</a-button>
          <a-button type="primary" :loading="saving" @click="rotate(selected)">{{ t('rotateSecret') }}</a-button>
        </a-space>
        <a-space v-else>
          <a-button :disabled="saving" @click="drawer = undefined">{{ t('cancelAction') }}</a-button>
          <a-button type="primary" html-type="submit" form="payment-merchant-form" :loading="saving">
            {{ t(drawer === 'create' ? 'issueCredential' : 'saveMerchant') }}
          </a-button>
        </a-space>
      </div>
    </template>
  </a-drawer>
</template>
