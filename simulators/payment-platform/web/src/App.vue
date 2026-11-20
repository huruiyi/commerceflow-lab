<script setup lang="ts">
import { computed, ref } from 'vue';
import { theme } from 'ant-design-vue';
import AdminShell from '@commerceflow/simulator-admin/shell';
import { useRoute, useRouter } from 'vue-router';
import { authState } from './main';
import { api, initializeCsrf } from './api';
import { locale, setLanguage, t } from './i18n';

const viewRevision = ref(0);
const route = useRoute();
const router = useRouter();
const publicPage = computed(() => Boolean(route.meta.public));
const mfaNotice = computed(() =>
  authState.session?.mfaRequired && !authState.session.mfaEnabled ? t('mfaNotice') : '',
);
const can = (authority: string) => authState.session?.authorities.includes(authority);

const menu = computed(() => {
  const item = (key: string, label: string, icon: string) => ({
    key,
    href: router.resolve(key).href,
    label: t(label),
    icon,
  });
  const groups = [
    { label: t('dashboard'), icon: 'dashboard', items: [item('/', 'paymentOverview', 'dashboard')] },
    {
      label: t('collectionsAndRefunds'),
      icon: 'card',
      items: [
        ...(can('TRANSACTION_READ') ? [item('/transactions', 'transactions', 'card')] : []),
        ...(can('REFUND_READ') ? [item('/refunds', 'refunds', 'refund')] : []),
      ],
    },
    {
      label: locale.value === 'en' ? 'Settlement' : '结算',
      icon: 'wallet',
      items: can('PAYOUT_READ') ? [item('/payouts', 'merchantPayouts', 'wallet')] : [],
    },
    {
      label: locale.value === 'en' ? 'Administration' : '系统管理',
      icon: 'settings',
      items: [
        ...(can('MERCHANT_MANAGE') ? [item('/merchants', 'merchants', 'merchant')] : []),
        ...(can('ADMIN_USER_MANAGE') ? [item('/users', 'users', 'users')] : []),
        ...(can('ROLE_MANAGE') ? [item('/roles', 'roleManagement', 'users')] : []),
        ...(can('AUDIT_READ') ? [item('/audit', 'audit', 'audit')] : []),
        item('/security', 'security', 'security'),
      ],
    },
  ];
  return groups.filter(({ items }) => items.length);
});

async function logout(lock = false) {
  await initializeCsrf();
  await api<void>('/api/auth/logout', { method: 'POST' });
  authState.session = null;
  authState.checked = true;
  await router.push(lock ? { path: '/login', query: { redirect: route.fullPath } } : '/login');
}
</script>

<template>
  <router-view v-if="publicPage" />
  <AdminShell
    v-else
    :title="t('appName')"
    :groups="menu"
    :active="route.path"
    :locale="locale === 'en' ? 'en-US' : 'zh-CN'"
    :account="authState.session?.displayName"
    :account-detail="authState.session?.email"
    account-page="/security"
    :notice="mfaNotice"
    can-change-language
    can-lock
    @navigate="router.push($event)"
    @language="setLanguage"
    @refresh="viewRevision++"
    @logout="logout()"
    @lock="logout(true)"
  >
    <template #default="{ dark }">
      <a-config-provider :theme="{ algorithm: dark ? theme.darkAlgorithm : theme.defaultAlgorithm }">
        <div v-if="mfaNotice" class="notice warn">{{ mfaNotice }}</div>
        <router-view :key="viewRevision" />
      </a-config-provider>
    </template>
  </AdminShell>
</template>
