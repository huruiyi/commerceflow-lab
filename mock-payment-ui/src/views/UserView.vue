<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, takeStepUpToken } from '../api'
import type { AdminUser, Merchant } from '../types'
import { enumLabel, t } from '../i18n'
const users = ref<AdminUser[]>([]); const merchants = ref<Merchant[]>([]); const message = ref(''); const error = ref('')
const form = ref({ username: '', password: 'Temporary@123', displayName: '', email: '', mobile: '', roleCodes: ['PAYMENT_OPERATOR'], requireMfa: true, requirePasswordChange: true })
const grant = ref({ userId: 0, merchantId: 0, permissionScope: 'OPERATE' })
async function load() { users.value = await api('/api/admin/users'); merchants.value = await api('/api/admin/merchants') }
onMounted(load)
async function create() { const token = takeStepUpToken(); if (!token) { error.value = t('stepUpTokenFirst'); return } try { await api('/api/admin/users', { method: 'POST', headers: { 'X-Step-Up-Token': token }, body: JSON.stringify(form.value) }); message.value = t('accountCreated'); await load() } catch(e) { error.value = e instanceof Error ? e.message : t('createFailed') } }
async function grantAccess() { const token = takeStepUpToken(); if (!token) { error.value = t('stepUpTokenFirst'); return } try { await api(`/api/admin/users/${grant.value.userId}/merchants`, { method: 'POST', headers: { 'X-Step-Up-Token': token }, body: JSON.stringify(grant.value) }); message.value = t('permissionUpdated') } catch(e) { error.value = e instanceof Error ? e.message : t('authorizationFailed') } }
</script>
<template>
  <div class="page-title"><div><span class="eyebrow">{{ t('identityAccess') }}</span><h1>{{ t('userTitle') }}</h1><p>{{ t('userDescription') }}</p></div></div>
  <a-alert v-if="message" type="success" show-icon :message="message" />
  <a-alert v-if="error" type="error" show-icon :message="error" />
  <div class="content-grid">
    <form class="card" @submit.prevent="create">
      <span class="eyebrow">{{ t('createIdentity') }}</span><h3>{{ t('createAdminUser') }}</h3>
      <label>{{ t('username') }}<input v-model="form.username" required /></label>
      <label>{{ t('displayName') }}<input v-model="form.displayName" required /></label>
      <label>{{ t('email') }}<input v-model="form.email" type="email" required /></label>
      <label>{{ t('temporaryPassword') }}<input v-model="form.password" type="password" minlength="12" required /></label>
      <label>{{ t('platformRole') }}<select v-model="form.roleCodes[0]"><option value="PAYMENT_ADMIN">{{ enumLabel('PAYMENT_ADMIN') }}</option><option value="PAYMENT_OPERATOR">{{ enumLabel('PAYMENT_OPERATOR') }}</option><option value="PAYMENT_AUDITOR">{{ enumLabel('PAYMENT_AUDITOR') }}</option></select></label>
      <label class="checkbox"><input v-model="form.requireMfa" type="checkbox" /> {{ t('requireMfa') }}</label>
      <label class="checkbox"><input v-model="form.requirePasswordChange" type="checkbox" /> {{ t('requirePasswordChange') }}</label>
      <a-button type="primary" html-type="submit" block>{{ t('createSecureIdentity') }}</a-button>
    </form>
    <article class="card span-2">
      <div class="card-head"><div><span class="eyebrow">{{ t('directory') }}</span><h3>{{ t('account') }}</h3></div></div>
      <vxe-table :data="users" border stripe show-overflow height="420">
        <vxe-column field="displayName" :title="t('account')" min-width="220"><template #default="{ row }"><b>{{ row.displayName }}</b><small>{{ row.username }} · {{ row.email }}</small></template></vxe-column>
        <vxe-column field="roles" :title="t('role')" min-width="190"><template #default="{ row }">{{ row.roles.map(enumLabel).join(', ') }}</template></vxe-column>
        <vxe-column field="mfaEnabled" :title="t('mfa')" width="160"><template #default="{ row }">{{ row.mfaEnabled ? t('enabled') : row.mfaRequired ? t('pendingEnrollment') : t('notRequired') }}</template></vxe-column>
        <vxe-column field="status" :title="t('status')" width="130"><template #default="{ row }"><a-tag :color="row.accountLocked ? 'error' : 'success'">{{ enumLabel(row.accountLocked ? 'LOCKED' : row.status) }}</a-tag></template></vxe-column>
      </vxe-table>
      <form class="grant-form" @submit.prevent="grantAccess"><h4>{{ t('assignMerchantScope') }}</h4><select v-model.number="grant.userId" required><option :value="0" disabled>{{ t('selectAccount') }}</option><option v-for="u in users" :key="u.id" :value="u.id">{{ u.displayName }}</option></select><select v-model.number="grant.merchantId" required><option :value="0" disabled>{{ t('selectMerchant') }}</option><option v-for="m in merchants" :key="m.id" :value="m.id">{{ m.displayName }}</option></select><select v-model="grant.permissionScope"><option v-for="scope in ['READ_ONLY','OPERATE','REFUND','AUDIT','ADMIN']" :key="scope" :value="scope">{{ enumLabel(scope) }}</option></select><a-button html-type="submit">{{ t('savePermission') }}</a-button></form>
    </article>
  </div>
</template>
