<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  admin,
  RequestError,
  type Channel,
  type Command,
  type Delivery,
  type DeliveryPage,
  type Fault,
} from './app-api';

const channels: { id: Channel; label: string; symbol: string }[] = [
  { id: 'SMS', label: '短信', symbol: '◫' },
  { id: 'EMAIL', label: '邮件', symbol: '✉' },
];
const states = { ACCEPTED: '已受理', DELIVERED: '已送达', FAILED: '送达失败' };
const formats = new Intl.DateTimeFormat('zh-CN', {
  dateStyle: 'medium',
  timeStyle: 'medium',
});
const tokenInput = ref('');
const token = ref('');
const faultsEnabled = ref(false);
const channel = ref<Channel>('SMS');
const status = ref('');
const reference = ref('');
const records = ref<Delivery[]>([]);
const selected = ref<Delivery | null>(null);
const total = ref(0);
const page = ref(1);
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
const pageCount = computed(() => Math.max(1, Math.ceil(total.value / 20)));

function readable(failure: unknown) {
  return failure instanceof RequestError ? failure.message : '连接失败，请检查本地服务后重试。';
}

function time(value: string | null) {
  return value ? formats.format(new Date(value)) : '—';
}

async function signIn() {
  if (!tokenInput.value.trim() || loading.value) return;
  loading.value = true;
  error.value = '';
  try {
    const value = await admin<{ faultsEnabled: boolean }>(tokenInput.value.trim(), 'session');
    token.value = tokenInput.value.trim();
    faultsEnabled.value = value.faultsEnabled;
    tokenInput.value = '';
    await load(1);
  } catch (failure) {
    error.value = readable(failure);
  } finally {
    loading.value = false;
  }
}

function logout() {
  readSequence++;
  detailSequence++;
  token.value = '';
  records.value = [];
  selected.value = null;
  total.value = 0;
  loading.value = false;
  error.value = '';
  notice.value = '';
  composeOpen.value = false;
  retry.value = undefined;
}

async function load(number = page.value) {
  if (!token.value) return;
  const id = ++readSequence;
  loading.value = true;
  error.value = '';
  const params = new URLSearchParams({
    channel: channel.value,
    page: String(number),
  });
  if (status.value) params.set('status', status.value);
  if (reference.value.trim()) params.set('reference', reference.value.trim());
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

function choose(value: Channel) {
  if (channel.value === value || sending.value) return;
  detailSequence++;
  channel.value = value;
  status.value = reference.value = notice.value = '';
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
      if (!(failure instanceof RequestError) || failure.status !== 504) throw failure;
      selected.value = await admin<Delivery>(token.value, 'deliveries/' + command.requestId);
      notice.value = '提交响应超时，已通过原投递编号查到记录。';
    }
    retry.value = undefined;
    composeOpen.value = false;
    reference.value = status.value = '';
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
  <main v-if="!token" class="login">
    <form class="login-panel" @submit.prevent="signIn">
      <div class="brand-icon" aria-hidden="true">M</div>
      <h1>消息模拟平台</h1>
      <label>管理令牌<input v-model="tokenInput" type="password" autocomplete="off" required /></label>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <button class="primary" :disabled="loading">
        {{ loading ? '正在连接…' : '进入后台' }}
      </button>
    </form>
  </main>
  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand">
        <span class="brand-icon" aria-hidden="true">M</span>
        <div><strong>CommerceFlow</strong><small>消息模拟平台</small></div>
      </div>
      <nav aria-label="消息渠道">
        <button
          v-for="item in channels"
          :key="item.id"
          type="button"
          :class="{ active: channel === item.id }"
          :aria-current="channel === item.id ? 'page' : undefined"
          :disabled="sending"
          @click="choose(item.id)"
        >
          <span aria-hidden="true">{{ item.symbol }}</span> {{ item.label }}
        </button>
      </nav>
    </aside>
    <main class="workspace">
      <header class="top">
        <span>本地模拟环境</span><button type="button" :disabled="sending" @click="logout">退出</button>
      </header>
      <div class="page">
        <header class="heading">
          <div>
            <h1>{{ current.label }}投递</h1>
            <p>共 {{ total }} 条记录</p>
          </div>
          <button class="primary" type="button" :disabled="sending" @click="openCompose">新建模拟投递</button>
        </header>
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
        <section class="panel ledger" aria-label="投递账本">
          <form class="filters" @submit.prevent="load(1)">
            <label>
              投递状态
              <select v-model="status">
                <option value="">全部状态</option>
                <option v-for="(label, key) in states" :key="key" :value="key">
                  {{ label }}
                </option>
              </select>
            </label>
            <label>业务关联号<input v-model="reference" maxlength="120" placeholder="输入完整关联号" /></label>
            <button type="submit" :disabled="loading">查询</button>
            <button type="button" :disabled="loading" @click="load()">刷新</button>
          </form>
          <div class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>业务关联号</th>
                  <th>模拟收件人</th>
                  <th>投递状态</th>
                  <th>受理时间</th>
                  <th>操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="entry in records" :key="entry.requestId">
                  <td>{{ entry.reference }}</td>
                  <td>{{ entry.recipientMask }}</td>
                  <td>
                    <span class="state" :class="entry.status.toLowerCase()">{{ states[entry.status] }}</span>
                  </td>
                  <td>{{ time(entry.acceptedAt) }}</td>
                  <td>
                    <button type="button" class="link" @click="detail(entry.requestId)">详情</button>
                  </td>
                </tr>
                <tr v-if="!records.length">
                  <td class="empty" colspan="5">
                    {{ loading ? '正在读取投递…' : '没有符合条件的投递记录' }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="paging">
            <button type="button" :disabled="loading || page <= 1" @click="load(page - 1)">上一页</button>
            <span>{{ page }} / {{ pageCount }}</span>
            <button type="button" :disabled="loading || page >= pageCount" @click="load(page + 1)">下一页</button>
          </div>
        </section>
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
      </div>
    </main>
  </div>
</template>
