<script setup lang="ts">
import { formatAdminDate } from '@commerceflow/simulator-admin/preferences';
import { computed, ref } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import AdminShell from '@commerceflow/simulator-admin/shell';
import AdminLogin from '@commerceflow/simulator-admin/login';
import AdminGrid from '@commerceflow/simulator-admin/grid';
import DocumentDetails from './DocumentDetails.vue';
import {
  documentId,
  outboundLabels,
  returnLabels,
  warehouseApi,
  type Resource,
  type WarehouseDocument,
} from './documents';

const menu = [
  {
    label: '仓库作业',
    icon: 'warehouse',
    items: [
      { key: 'outbounds', label: '出库单', icon: 'outbound' },
      { key: 'returns', label: '退货单', icon: 'returns' },
    ],
  },
];
const tokenInput = ref('');
const token = ref('');
const resource = ref<Resource>('outbounds');
const data = ref<WarehouseDocument[]>([]);
const selected = ref<WarehouseDocument | null>(null);
const selectionVersion = ref(0);
const order = ref('');
const status = ref('');
const appliedOrder = ref('');
const appliedStatus = ref('');
const page = ref(1);
const size = ref(20);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const notice = ref('');
let querySequence = 0;
const isReturn = computed(() => resource.value === 'returns');
const labels = computed<Record<string, string>>(() => (isReturn.value ? returnLabels : outboundLabels));
const rows = computed(() => data.value.filter((entry) => !appliedStatus.value || entry.status === appliedStatus.value));
const columns = computed<VxeGridProps<WarehouseDocument>['columns']>(() => [
  {
    field: isReturn.value ? 'returnId' : 'outboundId',
    title: isReturn.value ? '退货单号' : '出库单号',
    minWidth: 225,
  },
  { field: 'orderId', title: '订单编号', width: 100 },
  { field: 'warehouseId', title: '仓库编号', width: 100 },
  {
    field: 'status',
    title: '状态',
    minWidth: 125,
    slots: { default: 'status' },
  },
  ...(isReturn.value
    ? [{ field: 'trackingNumber', title: '退货运单', minWidth: 160 }]
    : [
        {
          field: 'createdAt',
          title: '创建时间',
          minWidth: 185,
          slots: { default: 'createdAt' },
        },
      ]),
  {
    field: 'actions',
    title: '操作',
    width: 100,
    fixed: 'right',
    slots: { default: 'actionsCell' },
  },
]);

function report(failure: unknown) {
  error.value = failure instanceof Error ? failure.message : '连接仓库服务失败';
}

async function signIn() {
  if (loading.value) return;
  loading.value = true;
  error.value = '';
  try {
    const credential = tokenInput.value.trim();
    data.value = await warehouseApi<WarehouseDocument[]>(credential, resource.value);
    token.value = credential;
    tokenInput.value = '';
  } catch (failure) {
    report(failure);
  } finally {
    loading.value = false;
  }
}

async function load() {
  const request = ++querySequence;
  const suffix = appliedOrder.value ? '?orderId=' + encodeURIComponent(appliedOrder.value) : '';
  loading.value = true;
  error.value = '';
  try {
    const values = await warehouseApi<WarehouseDocument[]>(token.value, resource.value + suffix);
    if (request !== querySequence) return;
    data.value = values;
    page.value = Math.min(page.value, Math.max(1, Math.ceil(rows.value.length / size.value)));
    if (selected.value) {
      const id = documentId(selected.value);
      selected.value = values.find((entry) => documentId(entry) === id) ?? null;
    }
  } catch (failure) {
    if (request === querySequence) report(failure);
  } finally {
    if (request === querySequence) loading.value = false;
  }
}

function search() {
  selected.value = null;
  appliedOrder.value = String(order.value);
  appliedStatus.value = status.value;
  page.value = 1;
  void load();
}

function reset() {
  order.value = status.value = '';
  search();
}

function navigate(key: string) {
  if (saving.value || key === resource.value || (key !== 'outbounds' && key !== 'returns')) return;
  resource.value = key;
  data.value = [];
  notice.value = '';
  reset();
}

function changePage(number: number, pageSize: number) {
  page.value = number;
  size.value = pageSize;
}

function logout() {
  querySequence++;
  token.value = '';
  data.value = [];
  resource.value = 'outbounds';
  selected.value = null;
  order.value = status.value = appliedOrder.value = appliedStatus.value = notice.value = error.value = '';
  page.value = 1;
  loading.value = false;
}

async function save(path: string, body: object) {
  if (saving.value) return;
  saving.value = true;
  error.value = notice.value = '';
  try {
    selected.value = await warehouseApi<WarehouseDocument>(token.value, path, body);
    selectionVersion.value++;
    await load();
    notice.value = '仓库结果已登记。';
  } catch (failure) {
    report(failure);
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <AdminLogin
    v-if="!token"
    brand="CommerceFlow 仓库"
    title="登录仓库作业台"
    description="输入管理令牌，处理出库单和退货单。"
  >
    <form class="sim-auth-form" @submit.prevent="signIn">
      <label>管理令牌<input v-model="tokenInput" type="password" autocomplete="off" required /></label>
      <p v-if="error" class="sim-auth-error" role="alert">{{ error }}</p>
      <button class="sim-button primary" :disabled="loading">{{ loading ? '正在连接…' : '进入后台' }}</button>
    </form>
  </AdminLogin>
  <AdminShell
    v-else
    title="仓库作业台"
    :groups="menu"
    :active="resource"
    :busy="saving"
    :notice="error || notice"
    @navigate="navigate"
    @refresh="load"
    @logout="logout"
  >
    <p v-if="error" class="warehouse-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="warehouse-notice" role="status">{{ notice }}</p>
    <AdminGrid
      :key="resource"
      :id="'warehouse-' + resource"
      :title="isReturn ? '退货单列表' : '出库单列表'"
      :rows="rows"
      :columns="columns"
      :row-key="isReturn ? 'returnId' : 'outboundId'"
      :loading="loading"
      :page="page"
      :page-size="size"
      @search="search"
      @reset="reset"
      @refresh="load"
      @page="changePage"
    >
      <template #filters>
        <label
          >订单编号<input v-model="order" type="number" min="1" inputmode="numeric" placeholder="全部订单"
        /></label>
      </template>
      <template #advancedFilters>
        <label
          >状态
          <select v-model="status">
            <option value="">全部状态</option>
            <option v-for="(label, key) in labels" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </label>
      </template>
      <template #status="{ row }"
        ><span class="warehouse-status">{{ labels[row.status] }}</span></template
      >
      <template #createdAt="{ row }">{{ formatAdminDate(row.createdAt) }}</template>
      <template #actionsCell="{ row }">
        <button class="sim-text-button" :disabled="saving" @click="selected = row">详情</button>
      </template>
    </AdminGrid>
    <DocumentDetails
      v-if="selected"
      :key="documentId(selected) + selectionVersion"
      :document="selected"
      :saving="saving"
      @save="save"
      @close="selected = null"
    />
  </AdminShell>
</template>
