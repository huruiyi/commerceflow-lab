<script setup lang="ts">
import { formatAdminDate } from '@commerceflow/simulator-admin/preferences';
import { useAdminPageSize } from '@commerceflow/simulator-admin/pagination';
import { computed, ref, watch } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import AdminShell from '@commerceflow/simulator-admin/shell';
import AdminLogin from '@commerceflow/simulator-admin/login';
import AdminTokenLoginForm from '@commerceflow/simulator-admin/token-login';
import { useAdminTokenSession } from '@commerceflow/simulator-admin/token-session';
import AdminGrid from '@commerceflow/simulator-admin/grid';
import DocumentDetails from './DocumentDetails.vue';
import {
  documentId,
  outboundLabels,
  returnLabels,
  warehouseApi,
  type Resource,
  type WarehouseDocument,
  type WarehousePage,
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
const {
  token,
  restoring,
  remember,
  clear: clearSession,
  discardIfUnauthorized,
} = useAdminTokenSession('warehouse', signIn);
const resource = ref<Resource>('outbounds');
const data = ref<WarehouseDocument[]>([]);
const total = ref(0);
const selected = ref<WarehouseDocument | null>(null);
const detailsDialog = ref<HTMLDialogElement>();
const selectionVersion = ref(0);
const emptyFilters = { orderId: '', jobId: '', status: '', from: '', through: '' };
const filters = ref({ ...emptyFilters });
const appliedFilters = ref({ ...emptyFilters });
const page = ref(1);
const size = useAdminPageSize(() => 'warehouse-' + resource.value);
const loading = ref(false);
const saving = ref(false);
const error = ref('');
const notice = ref('');
let querySequence = 0;
const isReturn = computed(() => resource.value === 'returns');
const labels = computed<Record<string, string>>(() => (isReturn.value ? returnLabels : outboundLabels));
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

async function signIn(credential: string) {
  if (loading.value) return;
  loading.value = true;
  error.value = '';
  try {
    const result = await warehouseApi<WarehousePage>(credential, searchPath());
    applyPage(result);
    remember(credential);
  } catch (failure) {
    discardIfUnauthorized(failure);
    report(failure);
  } finally {
    loading.value = false;
  }
}

function searchPath() {
  const { orderId, jobId, status, from, through } = appliedFilters.value;
  const params = new URLSearchParams({ page: String(page.value), size: String(size.value) });
  if (orderId) params.set('orderId', orderId);
  if (jobId.trim()) params.set('jobId', jobId.trim());
  if (status) params.set('status', status);
  if (from) params.set('createdFrom', new Date(from + 'T00:00:00').toISOString());
  if (through) {
    const midnight = new Date(through + 'T00:00:00');
    midnight.setDate(midnight.getDate() + 1);
    params.set('createdBefore', midnight.toISOString());
  }
  return resource.value + '?' + params;
}

function applyPage(result: WarehousePage) {
  data.value = result.rows;
  total.value = result.total;
  page.value = result.page;
  if (selected.value) {
    const id = documentId(selected.value);
    selected.value = result.rows.find((entry) => documentId(entry) === id) ?? selected.value;
  }
}

async function load() {
  const request = ++querySequence;
  loading.value = true;
  error.value = '';
  try {
    const result = await warehouseApi<WarehousePage>(token.value, searchPath());
    if (request !== querySequence) return;
    applyPage(result);
  } catch (failure) {
    if (request === querySequence) {
      discardIfUnauthorized(failure);
      report(failure);
    }
  } finally {
    if (request === querySequence) loading.value = false;
  }
}

function search() {
  const { from, through } = filters.value;
  if (from && through && from > through) {
    error.value = '创建日期的起始日期不能晚于结束日期。';
    return;
  }
  selected.value = null;
  appliedFilters.value = { ...filters.value, orderId: String(filters.value.orderId) };
  page.value = 1;
  void load();
}

function reset() {
  filters.value = { ...emptyFilters };
  search();
}

function navigate(key: string) {
  if (saving.value || key === resource.value || (key !== 'outbounds' && key !== 'returns')) return;
  resource.value = key;
  data.value = [];
  total.value = 0;
  notice.value = '';
  reset();
}

function changePage(number: number, pageSize: number) {
  if (number === page.value && pageSize === size.value) return;
  page.value = pageSize === size.value ? number : 1;
  size.value = pageSize;
  selected.value = null;
  void load();
}

function openDetails(row: WarehouseDocument) {
  const dialog = detailsDialog.value;
  if (!dialog || saving.value) return;
  error.value = notice.value = '';
  selected.value = row;
  dialog.showModal();
  dialog.querySelector('.warehouse-drawer-body')?.scrollTo(0, 0);
}

function closeDetails() {
  if (!saving.value) selected.value = null;
}

watch(selected, (document) => {
  if (!document) detailsDialog.value?.close();
});

function logout() {
  querySequence++;
  clearSession();
  data.value = [];
  total.value = 0;
  resource.value = 'outbounds';
  selected.value = null;
  filters.value = { ...emptyFilters };
  appliedFilters.value = { ...emptyFilters };
  notice.value = error.value = '';
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
    :title="restoring ? '正在恢复登录' : '登录仓库作业台'"
    description="输入管理令牌，处理出库单和退货单。"
  >
    <AdminTokenLoginForm
      v-if="!restoring"
      demo-token="demo-warehouse-admin-token"
      :loading="loading"
      :error="error"
      @sign-in="signIn"
    />
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
    <p v-if="error && !selected" class="warehouse-error" role="alert">{{ error }}</p>
    <p v-if="notice && !selected" class="warehouse-notice" role="status">{{ notice }}</p>
    <AdminGrid
      :key="resource"
      :id="'warehouse-' + resource"
      :title="isReturn ? '退货单列表' : '出库单列表'"
      :rows="data"
      :total="total"
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
          >订单编号<input v-model="filters.orderId" type="number" min="1" inputmode="numeric" placeholder="全部订单"
        /></label>
        <label>
          {{ isReturn ? '退货单号' : '出库单号' }}
          <input v-model="filters.jobId" type="search" maxlength="36" />
        </label>
      </template>
      <template #advancedFilters>
        <label
          >状态
          <select v-model="filters.status">
            <option value="">全部状态</option>
            <option v-for="(label, key) in labels" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </label>
        <label>创建日期从<input v-model="filters.from" type="date" /></label>
        <label>创建日期至<input v-model="filters.through" type="date" /></label>
      </template>
      <template #status="{ row }"
        ><span class="warehouse-status">{{ labels[row.status] }}</span></template
      >
      <template #createdAt="{ row }">{{ formatAdminDate(row.createdAt) }}</template>
      <template #actionsCell="{ row }">
        <button type="button" class="sim-text-button" :disabled="saving" @click="openDetails(row)">详情</button>
      </template>
    </AdminGrid>
    <dialog
      ref="detailsDialog"
      class="warehouse-drawer"
      aria-labelledby="warehouse-detail-title"
      @cancel.prevent="closeDetails"
    >
      <header class="warehouse-drawer-header">
        <h2 id="warehouse-detail-title">{{ isReturn ? '退货单详情' : '出库单详情' }}</h2>
        <button type="button" class="sim-button" :disabled="saving" @click="closeDetails">关闭详情</button>
      </header>
      <div class="warehouse-drawer-body">
        <p v-if="error" class="warehouse-error" role="alert">{{ error }}</p>
        <p v-if="notice" class="warehouse-notice" role="status">{{ notice }}</p>
        <DocumentDetails
          v-if="selected"
          :key="documentId(selected) + selectionVersion"
          :document="selected"
          :saving="saving"
          @save="save"
        />
      </div>
    </dialog>
  </AdminShell>
</template>
