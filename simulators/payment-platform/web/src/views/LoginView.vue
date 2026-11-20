<script setup lang="ts">
import { ref } from 'vue';
import AdminLogin from '@commerceflow/simulator-admin/login';
import { useRoute, useRouter } from 'vue-router';
import { api, initializeCsrf } from '../api';
import { authState } from '../main';
import type { Session } from '../types';
import { languageLabel, locale, switchLanguage, t } from '../i18n';

const username = ref('payment-admin');
const password = ref('demo-password');
const loading = ref(false);
const error = ref('');
const router = useRouter();
const route = useRoute();

async function login() {
  if (loading.value) return;
  loading.value = true;
  error.value = '';
  try {
    await initializeCsrf();
    authState.session = await api<Session>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username: username.value, password: password.value }),
    });
    authState.checked = true;
    await router.push(String(route.query.redirect || '/'));
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : t('loginFailed');
  } finally {
    loading.value = false;
  }
}

function useAccount(value: string) {
  username.value = value;
  password.value = 'demo-password';
}
</script>

<template>
  <AdminLogin
    brand="CommerceFlow Pay"
    :title="t('loginTitle')"
    :description="t('loginHint')"
    :english="locale === 'en'"
  >
    <template #tools>
      <button class="sim-button" type="button" @click="switchLanguage">{{ languageLabel }}</button>
    </template>
    <form class="sim-auth-form login-card" @submit.prevent="login">
      <label>{{ t('username') }}<input v-model="username" autocomplete="username" required /></label>
      <label
        >{{ t('password') }}
        <input
          v-model="password"
          type="password"
          autocomplete="current-password"
          :placeholder="t('passwordPlaceholder')"
          required
        />
      </label>
      <p v-if="error" class="sim-auth-error" role="alert">{{ error }}</p>
      <button class="sim-button primary" :disabled="loading">
        {{ loading ? t('verifying') : t('secureLogin') }}
      </button>
      <div class="sim-auth-demo">
        <span>{{ t('demoAccounts') }} <code>demo-password</code></span>
        <div>
          <button class="sim-button" type="button" @click="useAccount('payment-admin')">
            {{ t('administrator') }}
          </button>
          <button class="sim-button" type="button" @click="useAccount('payment-operator')">{{ t('operator') }}</button>
          <button class="sim-button" type="button" @click="useAccount('payment-auditor')">{{ t('auditor') }}</button>
        </div>
      </div>
    </form>
  </AdminLogin>
</template>
