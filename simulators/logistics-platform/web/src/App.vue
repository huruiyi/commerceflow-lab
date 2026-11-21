<script setup lang="ts">
import AdminGrid from '@commerceflow/simulator-admin/grid';
import AdminLogin from '@commerceflow/simulator-admin/login';
import { formatAdminDate } from '@commerceflow/simulator-admin/preferences';
import AdminShell from '@commerceflow/simulator-admin/shell';
import { computed, nextTick, ref, watch } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import { admin, callbackStates, shipmentStates } from './api';
import type { AuditEvent, Callback, Shipment, ShipmentPage, ShipmentSummary, TestCallbackResult } from './api';
import { callbackAuditText, callbackAuditTypes } from './callbackAudit';
import type { CallbackAuditType } from './callbackAudit';

type Page = 'shipments' | 'callbacks';
type Operation = 'advance' | 'exception' | 'recover' | 'replay';

const groups = [
  {
    label: '物流作业',
    icon: 'warehouse',
    items: [
      { key: 'shipments', label: '运单管理', icon: 'outbound' },
      { key: 'callbacks', label: '回调投递', icon: 'audit' },
    ],
  },
];
const shipmentColumns: VxeGridProps<ShipmentSummary>['columns'] = [
  { field: 'merchantShipmentNo', title: '商城运单号', minWidth: 180 },
  { field: 'orderNumber', title: '订单号', minWidth: 180 },
  { field: 'trackingNumber', title: '物流单号', minWidth: 145 },
  { field: 'status', title: '状态', width: 105, slots: { default: 'status' } },
  {
    field: 'updatedAt',
    title: '更新时间',
    width: 185,
    slots: { default: 'time' },
  },
  {
    field: 'actions',
    title: '操作',
    width: 80,
    fixed: 'right',
    slots: { default: 'operations' },
  },
];
const callbackColumns: VxeGridProps<Callback>['columns'] = [
  { field: 'eventId', title: '事件编号', width: 288 },
  { field: 'shipmentId', title: '运单 ID', width: 92 },
  {
    field: 'status',
    title: '投递状态',
    minWidth: 128,
    slots: { default: 'status' },
  },
  {
    field: 'attempts',
    title: '已尝试 / 上限',
    width: 130,
    slots: { default: 'attempts' },
  },
  {
    field: 'updatedAt',
    title: '更新时间',
    minWidth: 185,
    slots: { default: 'time' },
  },
  {
    field: 'actions',
    title: '操作',
    width: 80,
    fixed: 'right',
    slots: { default: 'operations' },
  },
];
const operationLabels: Record<Operation, string> = {
  advance: '推进一站',
  exception: '报告异常',
  recover: '恢复运输',
  replay: '重新投递',
};
const tokenInput = ref('');
const token = ref('');
const active = ref<Page>('shipments');
const reference = ref('');
const state = ref('');
const applied = ref({ reference: '', state: '' });
const shipmentRows = ref<ShipmentSummary[]>([]);
const callbackRows = ref<Callback[]>([]);
const shipment = ref<Shipment>();
const callback = ref<Callback>();
const auditEvents = ref<AuditEvent[]>([]);
const auditFilter = ref<CallbackAuditType | ''>('');
const newAuditCount = ref(0);
const auditPage = ref(1);
const auditPageSize = 10;
const auditPageHeadId = ref<number>();
const auditTimeline = computed(() =>
  auditEvents.value
    .slice()
    .reverse()
    .map((event) => ({ ...event, ...callbackAuditText(event) }))
    .filter((event) => !auditFilter.value || event.type === auditFilter.value),
);
const auditPagePool = computed(() => {
  const headIndex = auditTimeline.value.findIndex((event) => event.id === auditPageHeadId.value);
  return headIndex > 0 ? auditTimeline.value.slice(headIndex) : auditTimeline.value;
});
const auditPageCount = computed(() => Math.max(1, Math.ceil(auditPagePool.value.length / auditPageSize)));
const auditPageStart = computed(() => (auditPage.value - 1) * auditPageSize);
const auditPageRows = computed(() =>
  auditPagePool.value.slice(auditPageStart.value, auditPageStart.value + auditPageSize),
);
const page = ref(1);
const total = ref(0);
const loading = ref(false);
const busy = ref(false);
const error = ref('');
const notice = ref('');
const operation = ref<Operation>();
const location = ref('');
const note = ref('');
const operatedBy = ref('');
const createDialog = ref<HTMLDialogElement>();
const detailDialog = ref<HTMLDialogElement>();
const auditHeading = ref<HTMLElement>();
const auditScroller = ref<HTMLElement>();
const createRequest = ref('');
const carrier = ref('');
const tracking = ref('');
const rejectCallback = ref(false);
const createError = ref('');
const savedTestResult = ref<TestCallbackResult>();
const draftTestResult = ref<TestCallbackResult>();
const testResultChanged = computed(() => savedTestResult.value !== draftTestResult.value);
const clock = ref(Date.now());
const callbackRefreshFailed = ref(false);
let listRequest = 0;
let detailRequest = 0;
let callbackRequest = 0;

const isActiveShipment = computed(() => shipment.value && !['EXCEPTION', 'DELIVERED'].includes(shipment.value.status));

function time(value: string | null) {
  return value ? formatAdminDate(value) : '—';
}

function retryCountdown(record: Callback) {
  if (record.status !== 'RETRY' || !record.nextAttemptAt) return '';
  const seconds = Math.ceil((Date.parse(record.nextAttemptAt) - clock.value) / 1000);
  if (!Number.isFinite(seconds)) return '';
  if (seconds <= 0) return '等待调度';
  const minutes = Math.floor(seconds / 60);
  return minutes ? `${minutes} 分 ${seconds % 60} 秒` : `${seconds} 秒`;
}

function readable(failure: unknown) {
  return failure instanceof Error && failure.name === 'Error'
    ? failure.message
    : '无法确认服务响应，请刷新后查看当前状态。';
}

async function signIn() {
  const entered = tokenInput.value.trim();
  if (!entered || loading.value) return;
  loading.value = true;
  error.value = '';
  try {
    await admin<void>(entered, 'session');
    token.value = entered;
    tokenInput.value = '';
    await load();
  } catch (failure) {
    error.value = readable(failure);
  } finally {
    loading.value = false;
  }
}

function closeDetail() {
  detailDialog.value?.close();
  detailRequest++;
  shipment.value = callback.value = undefined;
  auditEvents.value = [];
  auditFilter.value = '';
  resetAuditPage();
  operation.value = undefined;
  savedTestResult.value = draftTestResult.value = undefined;
}

function openCreate() {
  closeDetail();
  createRequest.value = crypto.randomUUID();
  carrier.value = '模拟快递';
  tracking.value = '';
  rejectCallback.value = false;
  createError.value = '';
  createDialog.value?.showModal();
}

async function createShipment() {
  if (busy.value || !token.value) return;
  const provider = carrier.value.trim();
  if (!provider) {
    createError.value = '请输入承运商。';
    return;
  }
  busy.value = true;
  createError.value = '';
  error.value = notice.value = '';
  try {
    const created = await admin<Shipment>(token.value, 'shipments', 'POST', {
      requestId: createRequest.value,
      carrier: provider,
      trackingNumber: tracking.value.trim(),
      rejectCallback: rejectCallback.value,
    });
    createDialog.value?.close();
    reference.value = created.merchantShipmentNo;
    state.value = '';
    search();
    shipment.value = created;
    detailDialog.value?.showModal();
    notice.value = '测试运单已创建';
    await loadTestCallbackResult(created.id);
  } catch (failure) {
    createError.value = readable(failure);
  } finally {
    busy.value = false;
  }
}

function keepDialogOpenWhileBusy(event: Event) {
  if (busy.value) event.preventDefault();
}

function logout() {
  listRequest++;
  closeDetail();
  token.value = '';
  shipmentRows.value = [];
  callbackRows.value = [];
  total.value = 0;
  loading.value = false;
  error.value = notice.value = '';
}

async function load(nextPage = page.value, background = false) {
  if (!token.value || (background && loading.value)) return true;
  const request = ++listRequest;
  if (!background) {
    loading.value = true;
    error.value = '';
  }
  const params = new URLSearchParams({ status: applied.value.state });
  try {
    if (active.value === 'shipments') {
      params.set('reference', applied.value.reference);
      params.set('page', String(nextPage));
      const result = await admin<ShipmentPage>(token.value, 'shipments?' + params);
      if (request !== listRequest) return true;
      shipmentRows.value = result.entries;
      total.value = result.total;
      page.value = result.page;
    } else {
      if (applied.value.reference) params.set('shipmentId', applied.value.reference);
      const result = await admin<Callback[]>(token.value, 'callbacks?' + params);
      if (request !== listRequest) return true;
      const current = callbackRows.value;
      if (
        !background ||
        result.length !== current.length ||
        result.some((row, index) => row.id !== current[index].id || row.updatedAt !== current[index].updatedAt)
      ) {
        callbackRows.value = result;
      }
      page.value = Math.max(1, Math.min(background ? page.value : nextPage, Math.ceil(result.length / 20)));
    }
    return true;
  } catch (failure) {
    if (request !== listRequest) return true;
    if (!background) error.value = readable(failure);
    return false;
  } finally {
    if (!background && request === listRequest) loading.value = false;
  }
}

function search() {
  applied.value = { reference: reference.value.trim(), state: state.value };
  void load(1);
}

function reset() {
  reference.value = state.value = '';
  search();
}

function navigate(key: string, lookup = '') {
  if (busy.value || key === active.value || (key !== 'shipments' && key !== 'callbacks')) return;
  closeDetail();
  active.value = key;
  shipmentRows.value = [];
  callbackRows.value = [];
  total.value = 0;
  notice.value = '';
  reference.value = lookup;
  state.value = '';
  search();
}

async function showShipment(merchantNo: string) {
  if (busy.value) return;
  closeDetail();
  const request = detailRequest;
  error.value = '';
  try {
    const result = await admin<Shipment>(token.value, 'shipments/' + encodeURIComponent(merchantNo));
    if (request === detailRequest) {
      shipment.value = result;
      detailDialog.value?.showModal();
      await loadTestCallbackResult(result.id);
    }
  } catch (failure) {
    if (request === detailRequest) error.value = readable(failure);
  }
}

async function showCallback(row: Callback) {
  if (busy.value) return;
  closeDetail();
  callback.value = row;
  error.value = '';
  const dialog = detailDialog.value;
  if (dialog) {
    dialog.showModal();
    dialog.scrollTop = 0;
  }
  await Promise.all([refreshCallback(), loadTestCallbackResult(row.shipmentId)]);
}

function readingOlderAudit() {
  if (auditPage.value > 1) return true;
  const scroller = auditScroller.value;
  const latest = scroller?.querySelector('[data-audit-entry]');
  const header = scroller?.querySelector('thead');
  return Boolean(
    scroller &&
    scroller.scrollTop > 1 &&
    latest &&
    header &&
    latest.getBoundingClientRect().top < header.getBoundingClientRect().bottom - 1,
  );
}

function clearAuditNoticeAtLatest() {
  if (newAuditCount.value && !readingOlderAudit()) {
    resetAuditPage();
  }
}

function resetAuditPage() {
  auditPage.value = 1;
  auditPageHeadId.value = undefined;
  newAuditCount.value = 0;
  if (auditScroller.value) auditScroller.value.scrollTop = 0;
}

function goToLatestAudit() {
  resetAuditPage();
  auditHeading.value?.focus({ preventScroll: true });
}

function changeAuditPage(nextPage: number) {
  if (nextPage === 1) {
    resetAuditPage();
    return;
  }
  if (nextPage < 1 || nextPage > auditPageCount.value) return;
  auditPageHeadId.value ??= auditTimeline.value[0]?.id;
  auditPage.value = nextPage;
  if (auditScroller.value) auditScroller.value.scrollTop = 0;
}

async function updateCallbackDetail(record: Callback, events?: AuditEvent[]) {
  const scroller = auditScroller.value;
  const header = scroller?.querySelector('thead');
  const readingOlder = readingOlderAudit();
  let anchor: HTMLElement | undefined;
  const headerBottom = header?.getBoundingClientRect().bottom;
  if (scroller && readingOlder && headerBottom !== undefined) {
    const bottom = scroller.getBoundingClientRect().top + scroller.clientTop + scroller.clientHeight;
    anchor = Array.from(scroller.querySelectorAll<HTMLElement>('[data-audit-entry]')).find((entry) => {
      const bounds = entry.getBoundingClientRect();
      return bounds.bottom > headerBottom && bounds.top < bottom;
    });
  }
  const anchorTop = anchor?.getBoundingClientRect().top;
  callback.value = record;
  if (events) {
    if (readingOlder && auditEvents.value.length) {
      const existing = new Set(auditEvents.value.map((event) => event.id));
      const added = events.filter(
        (event) =>
          !existing.has(event.id) && (!auditFilter.value || callbackAuditText(event).type === auditFilter.value),
      ).length;
      if (added) {
        auditPageHeadId.value ??= auditTimeline.value[0]?.id;
        newAuditCount.value += added;
      }
    }
    auditEvents.value = events;
  }
  if (!scroller || !header || !anchor || anchorTop === undefined || headerBottom === undefined) return;
  await nextTick();
  if (anchor.isConnected) {
    const headerShift = header.getBoundingClientRect().bottom - headerBottom;
    scroller.scrollTop += anchor.getBoundingClientRect().top - anchorTop - headerShift;
  }
}

async function refreshCallback(background = false) {
  const selected = callback.value;
  if (!selected) return true;
  const detail = detailRequest;
  const request = ++callbackRequest;
  const path = 'callbacks/' + selected.id;
  try {
    const record = await admin<Callback>(token.value, path);
    if (detail !== detailRequest || request !== callbackRequest) return true;
    if (background && auditEvents.value.length && record.updatedAt === selected.updatedAt) return true;
    const events = await admin<AuditEvent[]>(token.value, path + '/events');
    if (detail !== detailRequest || request !== callbackRequest) return true;
    await updateCallbackDetail(record, events);
    return true;
  } catch (failure) {
    if (detail !== detailRequest || request !== callbackRequest) return true;
    if (!background) error.value = readable(failure);
    return false;
  }
}

watch(
  () => Boolean(token.value) && active.value === 'callbacks',
  (enabled, _previous, onCleanup) => {
    if (!enabled) return;
    let stopped = false;
    let polling = false;
    clock.value = Date.now();
    let lastPoll = clock.value;
    callbackRefreshFailed.value = false;

    async function tick(force = false) {
      if (document.hidden) return;
      clock.value = Date.now();
      const waiting = (record: Callback) => ['NEW', 'PROCESSING', 'RETRY'].includes(record.status);
      const interval = (callback.value && waiting(callback.value)) || callbackRows.value.some(waiting) ? 1000 : 2000;
      if (polling || loading.value || busy.value || (!force && clock.value - lastPoll < interval)) return;
      polling = true;
      lastPoll = clock.value;
      try {
        const [listOk, detailOk] = await Promise.all([load(page.value, true), refreshCallback(true)]);
        if (!stopped) callbackRefreshFailed.value = !listOk || !detailOk;
      } finally {
        polling = false;
      }
    }

    const timer = window.setInterval(() => void tick(), 500);
    const resume = () => void tick(true);
    document.addEventListener('visibilitychange', resume);
    onCleanup(() => {
      stopped = true;
      window.clearInterval(timer);
      document.removeEventListener('visibilitychange', resume);
    });
  },
);

async function loadTestCallbackResult(shipmentId: number) {
  const request = detailRequest;
  try {
    const result = await admin<{ result: TestCallbackResult } | undefined>(
      token.value,
      'shipments/' + shipmentId + '/test-callback-result',
    );
    if (request === detailRequest) savedTestResult.value = draftTestResult.value = result?.result;
  } catch (failure) {
    if (request === detailRequest) error.value = readable(failure);
  }
}

async function saveTestCallbackResult() {
  const result = draftTestResult.value;
  const shipmentId = shipment.value?.id ?? callback.value?.shipmentId;
  if (!result || !shipmentId || busy.value || !testResultChanged.value) return;
  const request = detailRequest;
  busy.value = true;
  error.value = notice.value = '';
  try {
    const saved = await admin<{ result: TestCallbackResult }>(
      token.value,
      'shipments/' + shipmentId + '/test-callback-result',
      'PUT',
      { result },
    );
    if (request !== detailRequest) return;
    savedTestResult.value = draftTestResult.value = saved.result;
    notice.value = '回调模拟结果已保存';
  } catch (failure) {
    if (request === detailRequest) error.value = readable(failure);
  } finally {
    busy.value = false;
  }
}

function prepare(action: Operation) {
  operation.value = action;
  location.value = note.value = operatedBy.value = '';
}

async function submit() {
  const action = operation.value;
  const session = token.value;
  if (!action || busy.value || !session) return;
  busy.value = true;
  error.value = notice.value = '';
  try {
    if (action === 'replay' && callback.value) {
      callbackRequest++;
      const path = 'callbacks/' + callback.value.id;
      const updated = await admin<Callback>(session, path + '/replay', 'POST', {
        operatedBy: operatedBy.value.trim(),
        reason: note.value.trim(),
      });
      if (session !== token.value) return;
      await updateCallbackDetail(updated);
      operation.value = undefined;
      const events = await admin<AuditEvent[]>(session, path + '/events');
      if (session !== token.value) return;
      await updateCallbackDetail(updated, events);
    } else if (shipment.value && action !== 'replay') {
      const path = 'shipments/' + encodeURIComponent(shipment.value.merchantShipmentNo) + '/' + action;
      const body =
        action === 'advance' ? undefined : { location: location.value.trim(), description: note.value.trim() };
      const updated = await admin<Shipment>(session, path, 'POST', body);
      if (session !== token.value) return;
      shipment.value = updated;
    }
    operation.value = undefined;
    notice.value = operationLabels[action] + '已提交';
    await load();
  } catch (failure) {
    if (session === token.value) error.value = readable(failure);
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <AdminLogin
    v-if="!token"
    brand="商流物流"
    title="登录物流模拟管理台"
    description="输入管理令牌，查看运单轨迹和回调投递。"
  >
    <form class="sim-auth-form" @submit.prevent="signIn">
      <label>
        管理令牌
        <input v-model="tokenInput" type="password" autocomplete="off" required />
      </label>
      <p v-if="error" class="sim-auth-error" role="alert">{{ error }}</p>
      <button class="sim-button primary" :disabled="loading">
        {{ loading ? '正在连接…' : '进入后台' }}
      </button>
    </form>
  </AdminLogin>
  <AdminShell
    v-else
    title="物流模拟管理台"
    :groups="groups"
    :active="active"
    :busy="busy"
    :notice="error || notice"
    @navigate="navigate"
    @refresh="load()"
    @logout="logout"
  >
    <p v-if="error" class="logistics-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="logistics-notice" role="status">{{ notice }}</p>
    <AdminGrid
      v-if="active === 'shipments'"
      key="shipments"
      class="logistics-grid"
      id="logistics-shipments"
      title="运单管理"
      :rows="shipmentRows"
      :columns="shipmentColumns"
      row-key="merchantShipmentNo"
      :total="total"
      :page="page"
      :loading="loading"
      fixed-page-size
      @search="search"
      @reset="reset"
      @refresh="load()"
      @page="load($event)"
    >
      <template #filters>
        <label>单号<input v-model="reference" maxlength="128" placeholder="订单号、运单号或物流单号" /></label>
      </template>
      <template #advancedFilters>
        <label>
          运单状态
          <select v-model="state">
            <option value="">全部状态</option>
            <option v-for="(label, key) in shipmentStates" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </label>
      </template>
      <template #status="{ row }">{{ shipmentStates[row.status] ?? row.status }}</template>
      <template #actions>
        <button type="button" class="sim-button primary" :disabled="busy" @click="openCreate">新建运单</button>
      </template>
      <template #time="{ row }">{{ time(row.updatedAt) }}</template>
      <template #operations="{ row }">
        <button type="button" class="sim-text-button" :disabled="busy" @click="showShipment(row.merchantShipmentNo)">
          详情
        </button>
      </template>
    </AdminGrid>
    <AdminGrid
      v-else
      key="callbacks"
      class="logistics-grid"
      id="logistics-callbacks"
      title="回调投递（最近 500 条）"
      :rows="callbackRows"
      :columns="callbackColumns"
      row-key="id"
      :page="page"
      :loading="loading"
      fixed-page-size
      @search="search"
      @reset="reset"
      @refresh="load()"
      @page="page = $event"
    >
      <template #filters>
        <label>
          运单 ID
          <input v-model="reference" inputmode="numeric" pattern="[0-9]+" maxlength="18" placeholder="输入运单 ID" />
        </label>
      </template>
      <template #advancedFilters>
        <label>
          投递状态
          <select v-model="state">
            <option value="">全部状态</option>
            <option v-for="(label, key) in callbackStates" :key="key" :value="key">
              {{ label }}
            </option>
          </select>
        </label>
      </template>
      <template #actions>
        <small v-if="callbackRefreshFailed" class="logistics-countdown" role="status">自动刷新暂时失败，正在重试</small>
      </template>
      <template #status="{ row }">
        {{ callbackStates[row.status] ?? row.status }}
        <small v-if="retryCountdown(row)" class="logistics-countdown inline-countdown">{{ retryCountdown(row) }}</small>
      </template>
      <template #time="{ row }">{{ time(row.updatedAt) }}</template>
      <template #attempts="{ row }">{{ row.attempts }} / {{ row.maxAttempts }}</template>
      <template #operations="{ row }">
        <button type="button" class="sim-text-button" :disabled="busy" @click="showCallback(row)">详情</button>
      </template>
    </AdminGrid>
    <dialog
      ref="createDialog"
      class="logistics-dialog"
      aria-labelledby="create-shipment-title"
      @cancel="keepDialogOpenWhileBusy"
    >
      <header>
        <h2 id="create-shipment-title">新建测试运单</h2>
      </header>
      <p>系统自动生成测试运单号和订单号。回调投递到模拟接收端，可在「回调投递」查看结果。</p>
      <form class="logistics-operation" @submit.prevent="createShipment">
        <label>
          承运商
          <input v-model="carrier" maxlength="100" required />
        </label>
        <label>
          物流单号
          <input v-model="tracking" maxlength="120" placeholder="留空自动生成" />
        </label>
        <label>
          回调模拟结果
          <select v-model="rejectCallback">
            <option :value="false">正常接收</option>
            <option :value="true">持续失败（用于验证重试）</option>
          </select>
        </label>
        <p v-if="createError" class="logistics-error" role="alert">{{ createError }}</p>
        <div class="logistics-actions">
          <button class="sim-button primary" :disabled="busy">{{ busy ? '正在创建…' : '创建测试运单' }}</button>
          <button type="button" class="sim-button" :disabled="busy" @click="createDialog?.close()">取消</button>
        </div>
      </form>
    </dialog>
    <dialog
      ref="detailDialog"
      class="logistics-dialog logistics-detail"
      :class="{ 'logistics-callback-detail': callback }"
      aria-labelledby="logistics-detail-title"
      @cancel.prevent="!busy && closeDetail()"
    >
      <header>
        <h2 id="logistics-detail-title">{{ shipment ? '运单详情' : '投递详情' }}</h2>
        <div v-if="callback" class="logistics-audit-updates">
          <span role="status">
            <span v-if="newAuditCount">新增 {{ newAuditCount }} 条</span>
          </span>
          <button v-if="newAuditCount" type="button" class="sim-button" @click="goToLatestAudit">回到最新</button>
        </div>
        <button type="button" class="sim-button" :disabled="busy" @click="closeDetail">关闭详情</button>
      </header>
      <p v-if="error" class="logistics-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="logistics-notice" role="status">{{ notice }}</p>
      <form
        v-if="savedTestResult"
        class="logistics-operation logistics-test-result"
        @submit.prevent="saveTestCallbackResult"
      >
        <label>
          回调模拟结果
          <select v-model="draftTestResult" :disabled="busy">
            <option value="ACCEPT">正常接收</option>
            <option value="REJECT">持续失败</option>
          </select>
        </label>
        <div class="logistics-actions">
          <button class="sim-button" :disabled="busy || !testResultChanged">保存模拟结果</button>
        </div>
        <small>设置用于此运单的后续投递。已终止的回调可在保存后手动重新投递。</small>
      </form>
      <template v-if="shipment">
        <dl>
          <dt>运单号</dt>
          <dd>{{ shipment.merchantShipmentNo }}</dd>
          <dt>运单 ID</dt>
          <dd>{{ shipment.id }}</dd>
          <dt>平台运单号</dt>
          <dd>{{ shipment.platformShipmentNo }}</dd>
          <dt>订单号</dt>
          <dd>{{ shipment.orderNumber }}</dd>
          <dt>承运商 / 物流单号</dt>
          <dd>{{ shipment.carrier }} / {{ shipment.trackingNumber }}</dd>
          <dt>当前状态</dt>
          <dd>{{ shipmentStates[shipment.status] ?? shipment.status }}</dd>
        </dl>
        <div class="logistics-actions">
          <button type="button" class="sim-button" :disabled="busy" @click="navigate('callbacks', String(shipment.id))">
            查看此运单回调
          </button>
          <button v-if="isActiveShipment" class="sim-button" :disabled="busy" @click="prepare('advance')">
            推进一站
          </button>
          <button v-if="isActiveShipment" class="sim-button" :disabled="busy" @click="prepare('exception')">
            报告异常
          </button>
          <button
            v-if="shipment.status === 'EXCEPTION'"
            class="sim-button"
            :disabled="busy"
            @click="prepare('recover')"
          >
            恢复运输
          </button>
        </div>
        <h3>物流轨迹</h3>
        <ol class="logistics-events">
          <li v-for="event in shipment.events" :key="event.eventId">
            <strong>{{ shipmentStates[event.status] ?? event.status }}</strong>
            <time>{{ time(event.occurredAt) }}</time>
            <p>{{ event.location }} · {{ event.description }}</p>
          </li>
        </ol>
      </template>
      <template v-else-if="callback">
        <p v-if="callbackRefreshFailed" class="logistics-countdown" role="status">自动刷新暂时失败，正在重试</p>
        <dl>
          <dt>事件编号</dt>
          <dd>{{ callback.eventId }}</dd>
          <dt>状态</dt>
          <dd>{{ callbackStates[callback.status] ?? callback.status }}</dd>
          <template v-if="callback.status === 'RETRY'">
            <dt>下次重试</dt>
            <dd>
              <strong v-if="retryCountdown(callback)">{{ retryCountdown(callback) }}</strong>
              <small class="logistics-countdown">{{ time(callback.nextAttemptAt) }}</small>
            </dd>
          </template>
          <dt>最近错误</dt>
          <dd>{{ callback.lastError || '—' }}</dd>
        </dl>
        <div class="logistics-actions">
          <button type="button" class="sim-button" :disabled="busy" @click="refreshCallback()">刷新投递状态</button>
          <button
            v-if="callback.status === 'DEAD'"
            class="sim-button"
            :disabled="busy || testResultChanged"
            @click="prepare('replay')"
          >
            重新投递
          </button>
        </div>
      </template>
      <form v-if="operation" class="logistics-operation logistics-operation-confirm" @submit.prevent="submit">
        <h3>确认{{ operationLabels[operation] }}</h3>
        <label v-if="operation === 'exception' || operation === 'recover'">
          地点<input v-model="location" maxlength="160" />
        </label>
        <label v-if="operation === 'replay'"> 操作人<input v-model="operatedBy" maxlength="160" required /> </label>
        <label v-if="operation !== 'advance'">
          {{ operation === 'replay' ? '重投原因' : '轨迹说明' }}
          <textarea v-model="note" :maxlength="operation === 'replay' ? 500 : 300" :required="operation === 'replay'" />
        </label>
        <div class="logistics-actions">
          <button
            class="sim-button primary"
            :disabled="busy || (operation === 'replay' && callback?.status !== 'DEAD')"
          >
            确认提交
          </button>
          <button type="button" class="sim-button" :disabled="busy" @click="operation = undefined">取消</button>
        </div>
      </form>
      <section v-if="callback" class="logistics-audit-panel" aria-labelledby="logistics-audit-title">
        <div class="logistics-audit-toolbar">
          <h3 id="logistics-audit-title" ref="auditHeading" tabindex="-1">审计记录</h3>
          <label>
            类型
            <select v-model="auditFilter" aria-label="审计记录类型" @change="resetAuditPage">
              <option value="">全部类型</option>
              <option v-for="(label, type) in callbackAuditTypes" :key="type" :value="type">{{ label }}</option>
            </select>
          </label>
        </div>
        <div ref="auditScroller" class="logistics-audit-scroll" tabindex="0" @scroll.passive="clearAuditNoticeAtLatest">
          <table class="logistics-audit-table" aria-labelledby="logistics-audit-title">
            <thead>
              <tr>
                <th scope="col">时间</th>
                <th scope="col">事件</th>
                <th scope="col">说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="event in auditPageRows" :key="event.id" data-audit-entry>
                <td>
                  <time :datetime="event.createdAt">{{ time(event.createdAt) }}</time>
                </td>
                <td>
                  <strong>{{ event.title }}</strong>
                  <small v-if="event.result" class="logistics-audit-result">{{ event.result }}</small>
                </td>
                <td>{{ event.detail || '—' }}</td>
              </tr>
              <tr v-if="!auditPageRows.length">
                <td colspan="3" class="logistics-audit-empty">
                  {{ auditFilter ? '暂无此类型的审计记录' : '暂无审计记录' }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <nav class="logistics-audit-pager" aria-label="审计记录分页">
          <small>共 {{ auditPagePool.length }} 条 · 第 {{ auditPage }} / {{ auditPageCount }} 页</small>
          <div class="logistics-actions">
            <button
              type="button"
              class="sim-button"
              :disabled="auditPage === 1"
              @click="changeAuditPage(auditPage - 1)"
            >
              上一页
            </button>
            <button
              type="button"
              class="sim-button"
              :disabled="auditPage >= auditPageCount"
              @click="changeAuditPage(auditPage + 1)"
            >
              下一页
            </button>
          </div>
        </nav>
      </section>
    </dialog>
  </AdminShell>
</template>
