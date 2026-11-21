<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { Modal } from 'ant-design-vue';
import AdminGrid from '@commerceflow/simulator-admin/grid';
import { useAdminPageSize } from '@commerceflow/simulator-admin/pagination';
import type { VxeGridProps } from 'vxe-table';
import PaymentLedger from '../PaymentLedger.vue';
import { api, takeStepUpToken } from '../api';
import type { AdminUser, Merchant, PaymentAdminRole, PaymentRoleBinding, RoleScope } from '../types';
import { enumLabel, locale, t } from '../i18n';

const users = ref<AdminUser[]>([]);
const merchants = ref<Merchant[]>([]);
const roles = ref<PaymentAdminRole[]>([]);
const bindings = ref<PaymentRoleBinding[]>([]);
const selectedId = ref<number>();
const selected = computed(() => users.value.find((user) => user.id === selectedId.value));
const drawer = ref<'create' | 'edit' | 'detail' | 'grant'>();
const selectedAccount = ref<AdminUser>();
const loading = ref(false);
const bindingsLoading = ref(false);
const saving = ref(false);
const message = ref('');
const error = ref('');
const drawerError = ref('');
const bindingError = ref('');
const scope = ref<RoleScope>('PLATFORM');
const rolePage = ref(1);
const roleTableId = computed(() =>
  scope.value === 'PLATFORM' ? 'payment-user-platform-bindings' : 'payment-user-merchant-bindings',
);
const rolePageSize = useAdminPageSize(roleTableId);

function newAccount() {
  return {
    username: '',
    password: '',
    displayName: '',
    email: '',
    mobile: '',
    roleCodes: [] as string[],
    requireMfa: true,
    requirePasswordChange: true,
  };
}

const form = ref(newAccount());
const grant = ref<{ merchantId?: number; roleIds: number[] }>({ roleIds: [] });
const roleOptions = computed(() =>
  roles.value.filter((role) => role.scope === 'PLATFORM').map((role) => ({ value: role.code, label: role.name })),
);
const merchantOptions = computed(() =>
  merchants.value.map((merchant) => ({ value: merchant.id, label: merchant.displayName })),
);
const grantOptions = computed(() =>
  roles.value
    .filter((role) => role.scope === scope.value)
    .filter(
      (role) =>
        !bindings.value.some(
          (binding) =>
            binding.roleId === role.id && (scope.value === 'PLATFORM' || binding.merchantId === grant.value.merchantId),
        ),
    )
    .map((role) => ({ value: role.id, label: role.name })),
);
const bindingRows = computed(() =>
  bindings.value
    .filter((binding) => binding.roleScope === scope.value)
    .map((binding) => ({ ...binding, key: `${binding.roleId}:${binding.merchantId ?? ''}` })),
);
const accounts = computed(() =>
  users.value.map((user) => ({ ...user, status: user.accountLocked ? 'LOCKED' : user.status })),
);
const drawerTitle = computed(() =>
  t(
    {
      create: 'createAdminUser',
      edit: 'editAccount',
      detail: 'accountDetails',
      grant: 'assignRoles',
    }[drawer.value ?? 'detail'],
  ),
);
const bindingColumns = computed<VxeGridProps<(typeof bindingRows.value)[number]>['columns']>(() => [
  ...(scope.value === 'MERCHANT'
    ? [
        {
          field: 'merchantName',
          title: t('merchantColumn'),
          minWidth: 105,
          showOverflow: 'title' as const,
        },
      ]
    : []),
  {
    field: 'roleName',
    title: t('role'),
    minWidth: 110,
    align: scope.value === 'PLATFORM' ? 'center' : 'left',
    showOverflow: 'title',
  },
  {
    field: 'enabled',
    title: t('status'),
    width: 92,
    formatter: ({ row }) => t(!row.roleEnabled ? 'roleGloballyDisabled' : row.enabled ? 'enabled' : 'disabled'),
  },
  { field: 'actions', title: t('accountActions'), width: 120, slots: { default: 'bindingActions' } },
]);

async function load() {
  loading.value = true;
  error.value = '';
  try {
    const [result, availableRoles, availableMerchants] = await Promise.all([
      api<AdminUser[]>('/api/admin/users'),
      api<PaymentAdminRole[]>('/api/admin/roles/options'),
      api<Merchant[]>('/api/admin/merchants'),
    ]);
    users.value = result;
    roles.value = availableRoles;
    merchants.value = availableMerchants;
    if (!result.some((account) => account.id === selectedId.value)) selectedId.value = result[0]?.id;
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('loadAccountsFailed');
  } finally {
    loading.value = false;
  }
}

async function loadBindings(id = selectedId.value) {
  if (id === undefined) return;
  bindingsLoading.value = true;
  bindingError.value = '';
  try {
    const result = await api<PaymentRoleBinding[]>(`/api/admin/users/${id}/role-bindings`);
    if (selectedId.value === id) bindings.value = result;
  } catch (e) {
    if (selectedId.value === id) bindingError.value = e instanceof Error ? e.message : t('loadRolesFailed');
  } finally {
    if (selectedId.value === id) bindingsLoading.value = false;
  }
}

onMounted(load);
watch(selectedId, (id) => {
  bindings.value = [];
  scope.value = 'PLATFORM';
  rolePage.value = 1;
  void loadBindings(id);
});
watch(scope, () => {
  rolePage.value = 1;
});
watch(
  () => bindingRows.value.length,
  (count) => {
    rolePage.value = Math.min(rolePage.value, Math.max(1, Math.ceil(count / rolePageSize.value)));
  },
);

function selectAccount(account: AdminUser) {
  selectedId.value = account.id;
}

function changeRolePage(page: number, size: number) {
  rolePage.value = size === rolePageSize.value ? page : 1;
  rolePageSize.value = size;
}

function openCreate() {
  form.value = newAccount();
  drawerError.value = '';
  drawer.value = 'create';
}

function openAccount(account: AdminUser, mode: 'detail' | 'edit') {
  selectedAccount.value = account;
  form.value = { ...newAccount(), username: account.username, displayName: account.displayName, email: account.email };
  drawerError.value = '';
  drawer.value = mode;
}

function openGrant() {
  if (!selected.value) return;
  selectedAccount.value = selected.value;
  grant.value = { roleIds: [] };
  drawerError.value = '';
  drawer.value = 'grant';
}

function closeDrawer() {
  drawer.value = undefined;
  form.value.password = '';
}

async function save() {
  const mode = drawer.value;
  const account = selectedAccount.value;
  if (saving.value || (mode !== 'create' && (!account || (mode !== 'edit' && mode !== 'grant')))) return;
  const token = takeStepUpToken();
  if (!token) {
    drawerError.value = t('stepUpTokenFirst');
    return;
  }
  drawerError.value = '';
  message.value = '';
  saving.value = true;
  try {
    const headers = { 'X-Step-Up-Token': token };
    if (mode === 'create') {
      const created = await api<AdminUser>('/api/admin/users', {
        method: 'POST',
        headers,
        body: JSON.stringify(form.value),
      });
      users.value = [...users.value, created];
      selectedId.value = created.id;
      message.value = t('accountCreated');
    } else if (mode === 'edit' && account) {
      const { displayName, email } = form.value;
      const updated = await api<AdminUser>(`/api/admin/users/${account.id}`, {
        method: 'PUT',
        headers,
        body: JSON.stringify({ displayName, email }),
      });
      users.value = users.value.map((user) => (user.id === updated.id ? updated : user));
      message.value = t('accountUpdated');
    } else if (account) {
      await api(`/api/admin/users/${account.id}/role-bindings`, {
        method: 'PUT',
        headers,
        body: JSON.stringify(grant.value),
      });
      message.value = t('permissionUpdated');
      await Promise.all([load(), loadBindings(account.id)]);
    }
    closeDrawer();
  } catch (e) {
    drawerError.value = e instanceof Error ? e.message : t('accountUpdateFailed');
  } finally {
    saving.value = false;
  }
}

async function changeBinding(binding: PaymentRoleBinding, enabled?: boolean) {
  const id = selectedId.value;
  if (id === undefined || saving.value) return;
  const token = takeStepUpToken();
  if (!token) {
    bindingError.value = t('stepUpTokenFirst');
    return;
  }
  const query = new URLSearchParams();
  if (binding.merchantId != null) query.set('merchantId', String(binding.merchantId));
  if (enabled !== undefined) query.set('enabled', String(enabled));
  saving.value = true;
  bindingError.value = '';
  try {
    await api(`/api/admin/users/${id}/role-bindings/${binding.roleId}?${query}`, {
      method: enabled === undefined ? 'DELETE' : 'PUT',
      headers: { 'X-Step-Up-Token': token },
    });
    await Promise.all([load(), loadBindings(id)]);
  } catch (e) {
    if (id === selectedId.value) bindingError.value = e instanceof Error ? e.message : t('authorizationFailed');
  } finally {
    saving.value = false;
  }
}

function removeBinding(binding: PaymentRoleBinding) {
  Modal.confirm({
    title: t('removeRolePrompt', { name: binding.roleName }),
    okText: t('removeRoleBinding'),
    cancelText: t('cancelAction'),
    onOk: () => changeBinding(binding),
  });
}

function rolesLabel(user: AdminUser) {
  return user.roles.map((code) => roles.value.find((role) => role.code === code)?.name ?? enumLabel(code)).join(' / ');
}

function mfaLabel(user: AdminUser) {
  return t(user.mfaEnabled ? 'enabled' : user.mfaRequired ? 'pendingEnrollment' : 'notRequired');
}

const columns = computed<VxeGridProps<AdminUser>['columns']>(() => [
  { field: 'username', title: t('username'), minWidth: 115, slots: { default: 'username' } },
  { field: 'displayName', title: t('displayName'), minWidth: 110, align: 'left', showOverflow: 'title' },
  {
    field: 'roles',
    title: t('role'),
    minWidth: 130,
    align: 'left',
    showOverflow: 'title',
    formatter: ({ row }) => rolesLabel(row),
  },
  { field: 'mfaEnabled', title: t('mfa'), minWidth: 65, formatter: ({ row }) => mfaLabel(row) },
  { field: 'status', title: t('status'), minWidth: 65, formatter: ({ row }) => enumLabel(row.status) },
  { field: 'actions', title: t('accountActions'), width: 135, slots: { default: 'accountActions' } },
]);
</script>

<template>
  <a-alert v-if="message" class="mb-3" type="success" show-icon closable :message="message" @close="message = ''" />
  <a-alert v-if="error" class="mb-3" type="error" show-icon :message="error" />
  <div class="account-layout grid min-h-0 flex-1 gap-4">
    <section class="account-list min-h-0 min-w-0">
      <PaymentLedger
        id="payment-user"
        :title="t('users')"
        :rows="accounts"
        :columns="columns"
        row-key="id"
        :search-fields="['username', 'displayName', 'email', 'roles']"
        :selected-row-key="selectedId"
        status-field="status"
        :loading="loading"
        @refresh="load"
        @select="selectAccount"
      >
        <template #actions>
          <a-button type="primary" @click="openCreate">{{ t('addAccount') }}</a-button>
        </template>
        <template #username="{ row }">
          <a-button size="small" type="link" @click.stop="selectAccount(row)">{{ row.username }}</a-button>
        </template>
        <template #accountActions="{ row }">
          <a-button type="link" size="small" @click.stop="openAccount(row, 'detail')">{{ t('details') }}</a-button>
          <a-button type="link" size="small" @click.stop="openAccount(row, 'edit')">{{
            t('editAccountAction')
          }}</a-button>
        </template>
      </PaymentLedger>
    </section>

    <section class="role-list flex min-h-0 min-w-0 flex-col">
      <a-alert v-if="bindingError" class="mb-3" type="error" show-icon :message="bindingError" />
      <a-segmented
        v-model:value="scope"
        class="mb-2"
        :options="[
          { value: 'PLATFORM', label: t('platformRole') },
          { value: 'MERCHANT', label: t('merchantAccess') },
        ]"
      />
      <AdminGrid
        :key="scope"
        :id="roleTableId"
        :title="selected ? selected.displayName : t('chooseAccountFirst')"
        :rows="bindingRows"
        :columns="bindingColumns"
        row-key="key"
        :locale="locale === 'en' ? 'en-US' : 'zh-CN'"
        :page="rolePage"
        :page-size="rolePageSize"
        :loading="bindingsLoading"
        :empty-text="t('noRoleBindings')"
        @page="changeRolePage"
        @refresh="loadBindings()"
      >
        <template #actions>
          <a-button type="primary" :disabled="!selected || bindingsLoading || saving" @click="openGrant">
            {{ t('assignRoles') }}
          </a-button>
        </template>
        <template #bindingActions="{ row }">
          <a-button
            type="link"
            size="small"
            :disabled="saving || (!row.enabled && !row.roleEnabled)"
            @click="changeBinding(row, !row.enabled)"
          >
            {{ t(row.enabled ? 'disableBinding' : 'enableBinding') }}
          </a-button>
          <a-button type="link" size="small" danger :disabled="saving" @click="removeBinding(row)">
            {{ t('removeRoleBinding') }}
          </a-button>
        </template>
      </AdminGrid>
    </section>
  </div>

  <a-drawer
    :open="Boolean(drawer)"
    :title="drawerTitle"
    width="min(100%, 480px)"
    :closable="!saving"
    :mask-closable="!saving"
    :keyboard="!saving"
    @close="closeDrawer"
  >
    <a-alert v-if="drawerError" class="mb-3" type="error" show-icon :message="drawerError" />
    <a-form
      v-if="drawer === 'create' || drawer === 'edit'"
      id="create-payment-account"
      :model="form"
      layout="horizontal"
      :label-col="{ flex: '7rem' }"
      :wrapper-col="{ flex: '1' }"
      @finish="save"
    >
      <a-form-item name="username" :label="t('username')" :rules="[{ required: true, whitespace: true }]">
        <a-input v-model:value="form.username" autocomplete="off" :readonly="drawer === 'edit'" />
      </a-form-item>
      <a-form-item name="displayName" :label="t('displayName')" :rules="[{ required: true, whitespace: true }]">
        <a-input v-model:value="form.displayName" />
      </a-form-item>
      <a-form-item name="email" :label="t('email')" :rules="[{ required: true, type: 'email' }]">
        <a-input v-model:value="form.email" type="email" />
      </a-form-item>
      <a-form-item
        v-if="drawer === 'create'"
        name="password"
        :label="t('temporaryPassword')"
        :rules="[{ required: true, min: 12 }]"
      >
        <a-input-password v-model:value="form.password" autocomplete="new-password" />
      </a-form-item>
      <a-form-item v-if="drawer === 'create'" name="roleCodes" :label="t('platformRole')">
        <a-select v-model:value="form.roleCodes" mode="multiple" :options="roleOptions" />
      </a-form-item>
      <a-space v-if="drawer === 'create'" direction="vertical">
        <a-checkbox v-model:checked="form.requireMfa">{{ t('requireMfa') }}</a-checkbox>
        <a-checkbox v-model:checked="form.requirePasswordChange">{{ t('requirePasswordChange') }}</a-checkbox>
      </a-space>
    </a-form>

    <a-descriptions
      v-else-if="drawer === 'detail' && selectedAccount"
      :column="1"
      :label-style="{ width: '32%', textAlign: 'left' }"
      :content-style="{ overflowWrap: 'anywhere' }"
      size="small"
      bordered
    >
      <a-descriptions-item :label="t('username')">{{ selectedAccount.username }}</a-descriptions-item>
      <a-descriptions-item :label="t('displayName')">{{ selectedAccount.displayName }}</a-descriptions-item>
      <a-descriptions-item :label="t('email')">{{ selectedAccount.email }}</a-descriptions-item>
      <a-descriptions-item :label="t('platformRole')">{{ rolesLabel(selectedAccount) }}</a-descriptions-item>
      <a-descriptions-item :label="t('mfa')">{{ mfaLabel(selectedAccount) }}</a-descriptions-item>
      <a-descriptions-item :label="t('status')">{{ enumLabel(selectedAccount.status) }}</a-descriptions-item>
    </a-descriptions>

    <a-form
      v-else-if="drawer === 'grant'"
      id="grant-payment-merchant"
      :model="grant"
      layout="horizontal"
      :label-col="{ flex: '7rem' }"
      :wrapper-col="{ flex: '1' }"
      @finish="save"
    >
      <a-form-item :label="t('account')">
        <a-input :value="selectedAccount?.username" :aria-label="t('account')" readonly />
      </a-form-item>
      <a-form-item
        v-if="scope === 'MERCHANT'"
        name="merchantId"
        :label="t('merchantColumn')"
        :rules="[{ required: true }]"
      >
        <a-select
          v-model:value="grant.merchantId"
          :options="merchantOptions"
          show-search
          option-filter-prop="label"
          @change="grant.roleIds = []"
        />
      </a-form-item>
      <a-form-item name="roleIds" :label="t('role')" :rules="[{ type: 'array', required: true, min: 1 }]">
        <a-select
          v-model:value="grant.roleIds"
          mode="multiple"
          :options="grantOptions"
          :disabled="scope === 'MERCHANT' && !grant.merchantId"
        />
      </a-form-item>
    </a-form>

    <template #footer>
      <div class="flex justify-end">
        <a-space>
          <a-button :disabled="saving" @click="closeDrawer">{{
            t(drawer === 'detail' ? 'close' : 'cancelAction')
          }}</a-button>
          <a-button
            v-if="drawer !== 'detail'"
            :form="drawer === 'grant' ? 'grant-payment-merchant' : 'create-payment-account'"
            type="primary"
            html-type="submit"
            :loading="saving"
          >
            {{ t(drawer === 'create' ? 'addAccount' : drawer === 'edit' ? 'saveAccount' : 'savePermission') }}
          </a-button>
        </a-space>
      </div>
    </template>
  </a-drawer>
</template>

<style scoped>
.account-layout {
  grid-template-areas: 'accounts roles';
  grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
  grid-template-rows: minmax(0, 1fr);
}

.account-list {
  grid-area: accounts;
}

.role-list {
  grid-area: roles;
}

@media (width < 1200px) {
  .account-layout {
    flex: none;
    grid-template-areas: 'roles' 'accounts';
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: 22rem minmax(22rem, 65dvh);
  }
}
</style>
