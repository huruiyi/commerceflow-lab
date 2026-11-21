<script setup lang="ts">
import { formatAdminDate } from '@commerceflow/simulator-admin/preferences';
import { useAdminPageSize } from '@commerceflow/simulator-admin/pagination';
import { computed, ref } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import AdminGrid from '@commerceflow/simulator-admin/grid';
import AdminShell from '@commerceflow/simulator-admin/shell';
import AdminLogin from '@commerceflow/simulator-admin/login';
import AdminTokenLoginForm from '@commerceflow/simulator-admin/token-login';
import { AdminRequestError, useAdminTokenSession } from '@commerceflow/simulator-admin/token-session';
import { admin, type Channel, type Command, type Delivery, type DeliveryPage, type Fault } from './app-api';

const channels: { id: Channel; label: string; symbol: string }[] = [
  { id: 'SMS', label: '短信', symbol: 'sms' },
  { id: 'EMAIL', label: '邮件', symbol: 'email' },
];
const states = { ACCEPTED: '已受理', DELIVERED: '已送达', FAILED: '送达失败' };
const {
  token,
  restoring,
  remember,
  clear: clearSession,
  discardIfUnauthorized,
} = useAdminTokenSession('messaging', signIn);
const faultsEnabled = ref(false);
const channel = ref<Channel>('SMS');
const status = ref('');
const reference = ref('');
const records = ref<Delivery[]>([]);
const selected = ref<Delivery | null>(null);
const total = ref(0);
const page = ref(1);
const size = useAdminPageSize(() => 'messaging-' + channel.value);
const loading = ref(false);
const sending = ref(false);
const error = ref('');
const notice = ref('');
const composeOpen = ref(false);
const recipient = ref('');
const tracking = ref('');
const subject = ref('');
const message = ref('');
const fault = ref<'' | Fault>('');
const retry = ref<{ key: string; command: Command }>();
let readSequence = 0;
let detailSequence = 0;
const current = computed(() => channels.find((value) => value.id === channel.value)!);
const groups = [
  {
    label: '消息投递',
    icon: 'messages',
    items: channels.map(({ id, label, symbol }) => ({
      key: id,
      label: label + '投递',
      icon: symbol,
    })),
  },
];
const columns: VxeGridProps<Delivery>['columns'] = [
  { field: 'reference', title: '业务关联号', minWidth: 240 },
  { field: 'recipientMask', title: '模拟收件人', minWidth: 150 },
  { field: 'status', title: '投递状态', width: 110, slots: { default: 'status' } },
  { field: 'acceptedAt', title: '受理时间', width: 190, slots: { default: 'acceptedAt' } },
  { field: 'actions', title: '操作', width: 85, fixed: 'right', slots: { default: 'operations' } },
];
const applied = ref({ status: '', reference: '' });

function search() {
  applied.value = { status: status.value, reference: reference.value.trim() };
  void load(1);
}

function reset() {
  status.value = reference.value = '';
  search();
}

function navigate(key: string) {
  if (key === 'SMS' || key === 'EMAIL') choose(key);
}

function readable(failure: unknown) {
  return failure instanceof AdminRequestError ? failure.message : '连接失败，请检查本地服务后重试。';
}

function time(value: string | null) {
  return value ? formatAdminDate(value) : '—';
}

async function signIn(entered: string) {
  if (loading.value) return;
  loading.value = true;
  error.value = '';
  try {
    const value = await admin<{ faultsEnabled: boolean }>(entered, 'session');
    remember(entered);
    faultsEnabled.value = value.faultsEnabled;
    await load(1);
  } catch (failure) {
    discardIfUnauthorized(failure);
    error.value = readable(failure);
  } finally {
    loading.value = false;
  }
}

function logout() {
  readSequence++;
  detailSequence++;
  clearSession();
  records.value = [];
  selected.value = null;
  total.value = 0;
  loading.value = false;
  error.value = '';
  notice.value = '';
  composeOpen.value = false;
  retry.value = undefined;
  status.value = reference.value = '';
  applied.value = { status: '', reference: '' };
}

async function load(number = page.value) {
  if (!token.value) return;
  const id = ++readSequence;
  loading.value = true;
  error.value = '';
  const params = new URLSearchParams({
    channel: channel.value,
    page: String(number),
    size: String(size.value),
  });
  if (applied.value.status) params.set('status', applied.value.status);
  if (applied.value.reference) params.set('reference', applied.value.reference);
  try {
    const result = await admin<DeliveryPage>(token.value, 'deliveries?' + params);
    if (id !== readSequence) return;
    records.value = result.entries;
    total.value = result.total;
    page.value = result.page;
  } catch (failure) {
    if (id === readSequence) error.value = readable(failure);
  } finally {
    if (id === readSequence) loading.value = false;
  }
}

function changePage(number: number, pageSize: number) {
  const nextPage = pageSize === size.value ? number : 1;
  size.value = pageSize;
  void load(nextPage);
}

function choose(value: Channel) {
  if (channel.value === value || sending.value) return;
  detailSequence++;
  channel.value = value;
  status.value = reference.value = notice.value = '';
  applied.value = { status: '', reference: '' };
  selected.value = null;
  records.value = [];
  composeOpen.value = false;
  void load(1);
}

function openCompose() {
  composeOpen.value = true;
  recipient.value = channel.value === 'SMS' ? '+12025550100' : 'demo@commerceflow.test';
  tracking.value = 'console-' + crypto.randomUUID().slice(0, 8);
  subject.value = '';
  message.value = '';
  fault.value = '';
  retry.value = undefined;
  notice.value = error.value = '';
}

async function submit() {
  if (sending.value) return;
  const payload = {
    channel: channel.value,
    recipient: recipient.value.trim(),
    reference: tracking.value.trim(),
    subject: channel.value === 'EMAIL' ? subject.value.trim() : undefined,
    message: message.value.trim(),
  };
  const key = JSON.stringify([payload, fault.value]);
  const previous = retry.value?.key === key ? retry.value : undefined;
  const command = previous?.command ?? {
    requestId: crypto.randomUUID(),
    ...payload,
  };
  sending.value = true;
  error.value = notice.value = '';
  try {
    if (!previous && fault.value) {
      await admin<void>(token.value, 'faults/' + command.requestId, 'PUT', {
        behavior: fault.value,
      });
    }
    retry.value = { key, command };
    try {
      selected.value = await admin<Delivery>(token.value, 'deliveries', 'POST', command);
      notice.value = '模拟投递已受理，可刷新查看最终结果。';
    } catch (failure) {
      if (!(failure instanceof AdminRequestError) || failure.status !== 504) throw failure;
      selected.value = await admin<Delivery>(token.value, 'deliveries/' + command.requestId);
      notice.value = '提交响应超时，已通过原投递编号查到记录。';
    }
    retry.value = undefined;
    composeOpen.value = false;
    reference.value = status.value = '';
    applied.value = { status: '', reference: '' };
    await load(1);
  } catch (failure) {
    error.value = readable(failure);
  } finally {
    sending.value = false;
  }
}

async function detail(id: string) {
  error.value = '';
  const request = ++detailSequence;
  try {
    const delivery = await admin<Delivery>(token.value, 'deliveries/' + id);
    if (request === detailSequence) selected.value = delivery;
  } catch (failure) {
    if (request === detailSequence) error.value = readable(failure);
  }
}
</script>

<template>
  <AdminLogin
    v-if="!token"
    brand="CommerceFlow 消息"
    :title="restoring ? '正在恢复登录' : '登录消息模拟平台'"
    description="输入管理令牌，查看短信和邮件投递记录。"
  >
    <AdminTokenLoginForm
      v-if="!restoring"
      demo-token="demo-messaging-admin-token"
      :loading="loading"
      :error="error"
      @sign-in="signIn"
    />
  </AdminLogin>
  <AdminShell
    v-else
    title="消息模拟平台"
    :groups="groups"
    :active="channel"
    :busy="sending"
    :notice="error || notice"
    @navigate="navigate"
    @refresh="load()"
    @logout="logout"
  >
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <form v-if="composeOpen" class="panel compose" @submit.prevent="submit">
      <h2>新建{{ current.label }}投递</h2>
      <div class="form-grid">
        <label>模拟收件人<input v-model="recipient" required /></label>
        <label>业务关联号<input v-model="tracking" maxlength="120" required /></label>
        <label v-if="channel === 'EMAIL'">主题<input v-model="subject" maxlength="160" required /></label>
        <label v-if="faultsEnabled">
          模拟结果
          <select v-model="fault">
            <option value="">正常送达</option>
            <option value="THROTTLE_ONCE">首次限流</option>
            <option value="TIMEOUT_AFTER_ACCEPT">受理后超时</option>
            <option value="DELIVERY_FAILED">送达失败</option>
          </select>
        </label>
        <label class="wide"
          >消息内容<textarea v-model="message" :maxlength="channel === 'SMS' ? 320 : 4000" required></textarea>
        </label>
      </div>
      <div class="actions">
        <button class="primary" :disabled="sending">
          {{ sending ? '正在提交…' : retry ? '重试原投递' : '提交模拟投递' }}
        </button>
        <button type="button" :disabled="sending" @click="composeOpen = false">关闭</button>
      </div>
    </form>
    <AdminGrid
      :key="channel"
      :id="'messaging-' + channel"
      :title="current.label + '投递列表'"
      :rows="records"
      :columns="columns"
      row-key="requestId"
      :total="total"
      :page="page"
      :page-size="size"
      :loading="loading"
      @search="search"
      @reset="reset"
      @refresh="load()"
      @page="changePage"
    >
      <template #filters>
        <label>业务关联号<input v-model="reference" maxlength="120" placeholder="输入完整关联号" /></label>
      </template>
      <template #advancedFilters>
        <label
          >投递状态
          <select v-model="status">
            <option value="">全部状态</option>
            <option v-for="(label, key) in states" :key="key" :value="key">{{ label }}</option>
          </select>
        </label>
      </template>
      <template #actions>
        <button class="sim-button primary" type="button" :disabled="sending" @click="openCompose">新建模拟投递</button>
      </template>
      <template #status="{ row }"
        ><span class="state" :class="row.status.toLowerCase()">{{
          states[row.status as keyof typeof states]
        }}</span></template
      >
      <template #acceptedAt="{ row }">{{ time(row.acceptedAt) }}</template>
      <template #operations="{ row }"
        ><button type="button" class="sim-text-button" @click="detail(row.requestId)">详情</button></template
      >
    </AdminGrid>
    <section v-if="selected" class="panel detail" aria-label="投递详情">
      <header>
        <h2>投递详情</h2>
        <button type="button" @click="selected = null">关闭详情</button>
      </header>
      <dl>
        <dt>投递编号</dt>
        <dd>{{ selected.requestId }}</dd>
        <dt>供应商单号</dt>
        <dd>{{ selected.providerId }}</dd>
        <dt>业务关联号</dt>
        <dd>{{ selected.reference }}</dd>
        <dt>模拟收件人</dt>
        <dd>{{ selected.recipientMask }}</dd>
        <dt>当前状态</dt>
        <dd>{{ states[selected.status] }}</dd>
        <dt>受理时间</dt>
        <dd>{{ time(selected.acceptedAt) }}</dd>
        <dt>结果时间</dt>
        <dd>{{ time(selected.finishedAt) }}</dd>
      </dl>
      <button type="button" @click="detail(selected.requestId)">刷新详情</button>
    </section>
  </AdminShell>
</template>
