<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue';
import { theme } from 'ant-design-vue';
import AdminLogin from '@commerceflow/simulator-admin/login';
import AdminShell from '@commerceflow/simulator-admin/shell';
import { api, type Session } from './api';
import Workspace from './Workspace.vue';
import RegionDemo from './RegionDemo.vue';

const menu = [
  {
    label: '概览',
    icon: 'dashboard',
    items: [{ key: 'overview', href: '#overview', label: '数据总览', icon: 'dashboard' }],
  },
  {
    label: '基础数据',
    icon: 'settings',
    items: [
      { key: 'versions', href: '#versions', label: '数据版本', icon: 'dashboard' },
      { key: 'nodes', href: '#nodes', label: '记录查询', icon: 'search' },
      { key: 'region-demo', href: '#region-demo', label: '区划联动', icon: 'list' },
      { key: 'publications', href: '#publications', label: '发布记录', icon: 'audit' },
    ],
  },
];

const title = '基础数据管理';
const panel = ref('overview');
const session = ref<Session>();
const checking = ref(true);
const busy = ref(false);
const revision = ref(0);
const error = ref('');
const email = ref('');
const password = ref('');

function fillDemoAdmin() {
  email.value = 'admin@example.com';
  password.value = 'demo-password';
  error.value = '';
}

function navigate(key: string) {
  location.hash = key;
}

function updatePanel() {
  const key = location.hash.slice(1);
  panel.value = menu.some((group) => group.items.some((item) => item.key === key)) ? key : 'overview';
}

onMounted(async () => {
  updatePanel();
  window.addEventListener('hashchange', updatePanel);
  try {
    const profile = await api<Session>('/api/auth/me');
    if (profile.role === 'ADMIN') session.value = profile;
  } catch {
    // The platform login form handles missing sessions.
  } finally {
    checking.value = false;
  }
});
onUnmounted(() => window.removeEventListener('hashchange', updatePanel));

async function login() {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    const result = await api<{ user: Session }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: email.value.trim(), password: password.value, rememberMe: false }),
    });
    if (result.user.role !== 'ADMIN') throw new Error('请使用商城平台管理员账号。');
    session.value = result.user;
    password.value = '';
  } catch (reason) {
    error.value = (reason as Error).message;
  } finally {
    busy.value = false;
  }
}

async function logout() {
  error.value = '';
  try {
    await api('/api/auth/logout', { method: 'POST' });
  } catch (reason) {
    error.value = (reason as Error).message;
  } finally {
    session.value = undefined;
  }
}
</script>

<template>
  <div v-if="checking" class="reference-loading" role="status">正在读取登录状态…</div>
  <AdminLogin v-else-if="!session" :brand="title" title="平台管理员登录" description="使用商城平台管理员账号">
    <form class="sim-auth-form login-card" @submit.prevent="login">
      <label>
        邮箱
        <input v-model="email" type="email" autocomplete="username" required />
      </label>
      <label>
        密码
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>
      <p v-if="error" class="sim-auth-error" role="alert">{{ error }}</p>
      <button class="sim-button primary" type="submit" :disabled="busy">
        {{ busy ? '登录中…' : '登录' }}
      </button>
      <div class="sim-auth-demo">
        <span>演示账号，密码为 <code>demo-password</code></span>
        <div>
          <button class="sim-button" type="button" :disabled="busy" @click="fillDemoAdmin">管理员</button>
        </div>
      </div>
    </form>
  </AdminLogin>
  <AdminShell
    v-else
    :title="title"
    :groups="menu"
    :active="panel"
    :account="session.firstName"
    :account-detail="session.email"
    @navigate="navigate"
    @refresh="revision++"
    @logout="logout"
  >
    <template #default="{ dark }">
      <a-config-provider :theme="{ algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm }">
        <RegionDemo v-if="panel === 'region-demo'" :key="revision" />
        <Workspace v-else :key="panel + ':' + revision" :panel="panel" />
      </a-config-provider>
    </template>
  </AdminShell>
</template>
