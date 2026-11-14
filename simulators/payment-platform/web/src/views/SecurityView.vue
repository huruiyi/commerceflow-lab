<script setup lang="ts">
import { computed, ref } from 'vue';
import QRCode from 'qrcode';
import { api, saveStepUpToken } from '../api';
import { authState } from '../main';
import { formatTime, t } from '../i18n';

const password = ref('');
const code = ref('');
const enrollment = ref<{ secret: string; otpauthUri: string } | null>(null);
const qr = ref('');
const recoveryCodes = ref<string[]>([]);
const stepUp = ref<{ token: string; expiresAt: string; factor: string } | null>(null);
const error = ref('');
const message = ref('');
const enabled = computed(() => authState.session?.mfaEnabled);

async function enroll() {
  error.value = '';
  message.value = '';
  try {
    const value = await api<{ secret: string; otpauthUri: string }>('/api/auth/mfa/enroll', {
      method: 'POST',
      body: JSON.stringify({ password: password.value }),
    });
    enrollment.value = value;
    qr.value = await QRCode.toDataURL(value.otpauthUri, { width: 220, margin: 1 });
    message.value = t('scanHint');
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('enrollmentFailed');
  }
}

async function confirm() {
  try {
    const result = await api<{ codes: string[]; notice: string }>('/api/auth/mfa/confirm', {
      method: 'POST',
      body: JSON.stringify({ code: code.value }),
    });
    recoveryCodes.value = result.codes;
    authState.session = await api('/api/auth/session');
    message.value = result.notice;
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('confirmFailed');
  }
}

async function authorize() {
  error.value = '';
  try {
    const value = await api<{ token: string; expiresAt: string; factor: string }>('/api/auth/step-up', {
      method: 'POST',
      body: JSON.stringify({ password: password.value, code: code.value }),
    });
    stepUp.value = value;
    saveStepUpToken(value.token);
    message.value = t('tokenGenerated', { factor: value.factor });
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('stepUpFailed');
  }
}

async function copyCodes() {
  await navigator.clipboard.writeText(recoveryCodes.value.join('\n'));
}
</script>

<template>
  <div class="page-title">
    <div>
      <span class="eyebrow">{{ t('securityCenter') }} · 05A-3</span>
      <h1>{{ t('securityTitle') }}</h1>
      <p>{{ t('securityDescription') }}</p>
    </div>
    <span :class="['badge', enabled ? 'success' : 'pending']">{{ enabled ? t('mfaEnabled') : t('mfaRequired') }}</span>
  </div>
  <div v-if="error" class="notice error">{{ error }}</div>
  <div v-if="message" class="notice">{{ message }}</div>
  <div class="content-grid">
    <section class="card">
      <span class="eyebrow">1 · {{ t('enrollment') }}</span>
      <h3>{{ t('bindAuthenticator') }}</h3>
      <p class="muted">{{ t('authenticatorHint') }}</p>
      <label
        >{{ t('currentPassword') }}<input v-model="password" type="password" autocomplete="current-password"
      /></label>
      <button class="primary full" @click="enroll">{{ t('generateTotp') }}</button>
    </section>
    <section class="card">
      <span class="eyebrow">2 · {{ t('verify') }}</span>
      <h3>{{ t('scanAndConfirm') }}</h3>
      <div v-if="enrollment" style="text-align: center">
        <img :src="qr" alt="TOTP QR code" width="220" height="220" />
        <p class="mono small">{{ enrollment.secret }}</p>
      </div>
      <div v-else class="empty">{{ t('generateFirst') }}</div>
      <label
        >{{ t('totpCode')
        }}<input v-model="code" inputmode="numeric" maxlength="14" :placeholder="t('totpPlaceholder')"
      /></label>
      <button class="secondary full" :disabled="!enrollment" @click="confirm">{{ t('confirmMfa') }}</button>
    </section>
    <section class="card">
      <span class="eyebrow">3 · {{ t('stepUp') }}</span>
      <h3>{{ t('authorizeSensitiveAction') }}</h3>
      <p class="muted">{{ t('stepUpHint') }}</p>
      <label>{{ t('totpOrRecovery') }}<input v-model="code" /></label>
      <button class="primary full" @click="authorize">{{ t('generateStepUp') }}</button>
      <div v-if="stepUp" class="notice success">{{ t('validUntil', { time: formatTime(stepUp.expiresAt) }) }}</div>
    </section>
  </div>
  <section v-if="recoveryCodes.length" class="secret-panel">
    <div>
      <span class="eyebrow">{{ t('recoveryCodes') }}</span>
      <h3>{{ t('saveRecoveryCodes') }}</h3>
      <p>{{ t('recoveryHint') }}</p>
    </div>
    <code style="white-space: pre-wrap">{{ recoveryCodes.join('\n') }}</code>
    <button @click="copyCodes">{{ t('copyAll') }}</button>
  </section>
</template>
