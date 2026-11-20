<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Modal } from 'ant-design-vue';
import type { VxeGridProps } from 'vxe-table';
import PaymentLedger from '../PaymentLedger.vue';
import { api, takeStepUpToken } from '../api';
import { t } from '../i18n';
import type { PaymentAdminRole, RoleScope } from '../types';

type RoleRow = PaymentAdminRole & { status: 'ENABLED' | 'DISABLED' };
type RoleDetail = {
  role: PaymentAdminRole;
  accounts: { userId: number; username: string; merchantId?: number; merchantName?: string; enabled: boolean }[];
};
const roles = ref<PaymentAdminRole[]>([]);
const catalog = ref<{ platform: string[]; systemPlatform: string[]; merchant: string[] }>({
  platform: [],
  systemPlatform: [],
  merchant: [],
});
const detail = ref<RoleDetail>();
const roleId = ref<number>();
const drawer = ref<'view' | 'edit'>();
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const drawerError = ref('');
const message = ref('');
const scopeOptions = computed(() => [
  { value: 'PLATFORM', label: t('platformRole') },
  { value: 'MERCHANT', label: t('merchantRole') },
]);
const rows = computed<RoleRow[]>(() =>
  roles.value.map((role) => ({
    ...role,
    status: role.enabled ? 'ENABLED' : 'DISABLED',
  })),
);

function newRole() {
  return {
    code: '',
    name: '',
    scope: 'MERCHANT' as RoleScope,
    description: '',
    enabled: true,
    permissions: [] as string[],
  };
}

const form = ref(newRole());
const editingRole = computed(() => roles.value.find((role) => role.id === roleId.value));
const protectedRole = computed(() => editingRole.value?.code === 'PAYMENT_ADMIN');
const permissionOptions = computed(() => {
  const options = protectedRole.value
    ? form.value.permissions
    : form.value.scope === 'MERCHANT'
      ? catalog.value.merchant
      : editingRole.value?.systemRole
        ? catalog.value.systemPlatform
        : catalog.value.platform;
  return options.map((value) => ({ value, label: permissionLabel(value) }));
});
watch(
  () => form.value.scope,
  () => {
    if (!roleId.value) {
      const allowed = permissionOptions.value.map(({ value }) => value);
      form.value.permissions = form.value.permissions.filter((permission) => allowed.includes(permission));
    }
  },
);

function scopeLabel(scope: RoleScope) {
  return t(scope === 'PLATFORM' ? 'platformRole' : 'merchantRole');
}

function permissionLabel(permission: string) {
  const key = `permission.${permission}`;
  const label = t(key);
  return label === key ? permission : label;
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const [list, permissions] = await Promise.all([
      api<PaymentAdminRole[]>('/api/admin/roles'),
      api<typeof catalog.value>('/api/admin/roles/permissions'),
    ]);
    roles.value = list;
    catalog.value = permissions;
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('loadRolesFailed');
  } finally {
    loading.value = false;
  }
}

onMounted(load);

function openEdit(role?: PaymentAdminRole) {
  roleId.value = role?.id;
  form.value = role
    ? {
        code: role.code,
        name: role.name,
        scope: role.scope,
        description: role.description,
        enabled: role.enabled,
        permissions: [...role.permissions],
      }
    : newRole();
  drawerError.value = '';
  drawer.value = 'edit';
}

async function openDetail(role: PaymentAdminRole) {
  drawerError.value = '';
  detail.value = undefined;
  drawer.value = 'view';
  roleId.value = role.id;
  try {
    const result = await api<RoleDetail>(`/api/admin/roles/${role.id}`);
    if (drawer.value === 'view' && roleId.value === role.id) detail.value = result;
  } catch (e) {
    if (drawer.value === 'view' && roleId.value === role.id) {
      drawerError.value = e instanceof Error ? e.message : t('loadRolesFailed');
    }
  }
}

async function save() {
  if (saving.value) return;
  drawerError.value = '';
  const token = takeStepUpToken();
  if (!token) {
    drawerError.value = t('stepUpTokenFirst');
    return;
  }
  saving.value = true;
  try {
    const id = roleId.value;
    await api(id ? `/api/admin/roles/${id}` : '/api/admin/roles', {
      method: id ? 'PUT' : 'POST',
      body: JSON.stringify(form.value),
      headers: { 'X-Step-Up-Token': token },
    });
    drawer.value = undefined;
    message.value = t('roleSaved');
    await load();
  } catch (e) {
    drawerError.value = e instanceof Error ? e.message : t('roleSaveFailed');
  } finally {
    saving.value = false;
  }
}

function deleteRole(role: PaymentAdminRole) {
  Modal.confirm({
    title: t('deleteRolePrompt', { name: role.name }),
    okText: t('deleteRole'),
    cancelText: t('cancelAction'),
    okType: 'danger',
    async onOk() {
      const token = takeStepUpToken();
      if (!token) {
        error.value = t('stepUpTokenFirst');
        return;
      }
      try {
        await api(`/api/admin/roles/${role.id}`, { method: 'DELETE', headers: { 'X-Step-Up-Token': token } });
        message.value = t('roleDeleted');
        await load();
      } catch (e) {
        error.value = e instanceof Error ? e.message : t('roleSaveFailed');
      }
    },
  });
}

function changeRoleStatus(role: PaymentAdminRole) {
  const enabled = !role.enabled;
  const action = t(enabled ? 'enableBinding' : 'disableBinding');
  Modal.confirm({
    title: t('roleStatusPrompt', { action, name: role.name }),
    content: enabled ? undefined : t('roleDisableImpact', { count: role.accountCount }),
    okText: action,
    okType: enabled ? 'primary' : 'danger',
    cancelText: t('cancelAction'),
    async onOk() {
      error.value = '';
      const token = takeStepUpToken();
      if (!token) {
        error.value = t('stepUpTokenFirst');
        return;
      }
      try {
        const updated = await api<PaymentAdminRole>(`/api/admin/roles/${role.id}/status`, {
          method: 'PATCH',
          body: JSON.stringify({ enabled }),
          headers: { 'X-Step-Up-Token': token },
        });
        roles.value = roles.value.map((item) => (item.id === updated.id ? updated : item));
        message.value = t(enabled ? 'roleEnabledMessage' : 'roleDisabledMessage');
      } catch (e) {
        error.value = e instanceof Error ? e.message : t('roleSaveFailed');
      }
    },
  });
}

const columns = computed<VxeGridProps<RoleRow>['columns']>(() => [
  { field: 'name', title: t('roleName'), minWidth: 145, showOverflow: 'title' },
  { field: 'code', title: t('roleCode'), minWidth: 150, align: 'left', showOverflow: 'title' },
  { field: 'scope', title: t('roleScope'), minWidth: 100, formatter: ({ row }) => scopeLabel(row.scope) },
  {
    field: 'status',
    title: t('status'),
    minWidth: 90,
    formatter: ({ row }) => t(row.enabled ? 'enabled' : 'disabled'),
  },
  { field: 'accountCount', title: t('accountCount'), minWidth: 85 },
  { field: 'description', title: t('description'), minWidth: 160, align: 'left', showOverflow: 'title' },
  { field: 'actions', title: t('accountActions'), width: 250, slots: { default: 'roleActions' } },
]);
</script>

<template>
  <a-alert v-if="message" class="mb-3" type="success" show-icon closable :message="message" @close="message = ''" />
  <a-alert v-if="error" class="mb-3" type="error" show-icon :message="error" />
  <PaymentLedger
    id="payment-role-management"
    :title="t('roleManagement')"
    :rows="rows"
    :columns="columns"
    :search-fields="['name', 'code', 'description']"
    status-field="status"
    row-key="id"
    :loading="loading"
    @refresh="load"
  >
    <template #actions>
      <a-button type="primary" @click="openEdit()">{{ t('addRole') }}</a-button>
    </template>
    <template #roleActions="{ row }">
      <a-button type="link" size="small" @click="openDetail(row)">{{ t('details') }}</a-button>
      <a-button type="link" size="small" @click="openEdit(row)">{{ t('editRole') }}</a-button>
      <a-button v-if="row.code !== 'PAYMENT_ADMIN'" type="link" size="small" @click="changeRoleStatus(row)">
        {{ t(row.enabled ? 'disableBinding' : 'enableBinding') }}
      </a-button>
      <a-button
        v-if="!row.systemRole"
        type="link"
        size="small"
        danger
        :disabled="row.accountCount > 0"
        @click="deleteRole(row)"
      >
        {{ t('deleteRole') }}
      </a-button>
    </template>
  </PaymentLedger>
  <a-drawer
    :open="Boolean(drawer)"
    :title="t(drawer === 'view' ? 'roleDetails' : roleId ? 'editRole' : 'addRole')"
    width="min(100%, 520px)"
    :closable="!saving"
    :mask-closable="!saving"
    :keyboard="!saving"
    @close="drawer = undefined"
  >
    <a-alert v-if="drawerError" class="mb-3" type="error" show-icon :message="drawerError" />
    <a-form
      v-if="drawer === 'edit'"
      id="payment-role-form"
      :model="form"
      layout="horizontal"
      :label-col="{ flex: '7rem' }"
      :wrapper-col="{ flex: '1' }"
      @finish="save"
    >
      <a-form-item name="name" :label="t('roleName')" :rules="[{ required: true, whitespace: true, max: 100 }]">
        <a-input v-model:value="form.name" />
      </a-form-item>
      <a-form-item
        name="code"
        :label="t('roleCode')"
        :rules="[{ required: true, pattern: /^[A-Z][A-Z0-9_]{2,63}$/, message: t('roleCodeFormat') }]"
      >
        <a-input v-model:value="form.code" :readonly="Boolean(roleId)" />
      </a-form-item>
      <a-form-item name="scope" :label="t('roleScope')">
        <a-select v-model:value="form.scope" :options="scopeOptions" :disabled="Boolean(roleId)" />
      </a-form-item>
      <a-form-item
        name="permissions"
        :label="t('rolePermissions')"
        :rules="[{ type: 'array', required: true, min: 1 }]"
      >
        <a-select
          v-model:value="form.permissions"
          mode="multiple"
          :options="permissionOptions"
          :disabled="protectedRole"
        />
      </a-form-item>
      <a-form-item name="description" :label="t('description')">
        <a-textarea v-model:value="form.description" :maxlength="255" :rows="3" />
      </a-form-item>
      <a-form-item :label="t('status')">
        <a-switch
          v-model:checked="form.enabled"
          :disabled="protectedRole"
          :checked-children="t('enabled')"
          :un-checked-children="t('disabled')"
        />
      </a-form-item>
      <a-alert v-if="protectedRole" type="info" :message="t('adminRoleProtected')" />
      <a-alert
        v-if="roleId && !form.enabled"
        type="warning"
        :message="t('roleDisableImpact', { count: roles.find((role) => role.id === roleId)?.accountCount ?? 0 })"
      />
    </a-form>
    <template v-else-if="detail">
      <a-descriptions :column="1" :label-style="{ width: '32%', textAlign: 'left' }" size="small" bordered>
        <a-descriptions-item :label="t('roleName')">{{ detail.role.name }}</a-descriptions-item>
        <a-descriptions-item :label="t('roleCode')">{{ detail.role.code }}</a-descriptions-item>
        <a-descriptions-item :label="t('roleScope')">{{ scopeLabel(detail.role.scope) }}</a-descriptions-item>
        <a-descriptions-item :label="t('rolePermissions')">{{
          detail.role.permissions.map(permissionLabel).join(' / ')
        }}</a-descriptions-item>
        <a-descriptions-item :label="t('description')">{{ detail.role.description || '—' }}</a-descriptions-item>
      </a-descriptions>
      <a-list class="mt-3" size="small" :header="t('assignedAccounts')" :data-source="detail.accounts">
        <template #renderItem="{ item }">
          <a-list-item>
            {{ item.username }}<template v-if="item.merchantName"> · {{ item.merchantName }}</template>
            <template #actions>{{ t(detail.role.enabled && item.enabled ? 'enabled' : 'disabled') }}</template>
          </a-list-item>
        </template>
      </a-list>
    </template>
    <template #footer>
      <div class="flex justify-end">
        <a-space>
          <a-button :disabled="saving" @click="drawer = undefined">{{
            t(drawer === 'view' ? 'close' : 'cancelAction')
          }}</a-button>
          <a-button
            v-if="drawer === 'edit'"
            form="payment-role-form"
            type="primary"
            html-type="submit"
            :loading="saving"
          >
            {{ t('saveRole') }}
          </a-button>
        </a-space>
      </div>
    </template>
  </a-drawer>
</template>
