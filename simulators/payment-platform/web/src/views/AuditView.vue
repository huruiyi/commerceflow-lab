<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import PaymentLedger from '../PaymentLedger.vue';
import { api } from '../api';
import { enumLabel, formatDate, t } from '../i18n';
import type { AuditLog } from '../types';

const logs = ref<AuditLog[]>([]);
async function load() {
  logs.value = await api('/api/admin/audit-logs');
}
onMounted(load);

const columns = computed<VxeGridProps<AuditLog>['columns']>(() => [
  { field: 'createdAt', title: t('time'), width: 190, slots: { default: 'createdAt' } },
  { field: 'actorUsername', title: t('actor'), width: 180, slots: { default: 'actorUsername' } },
  { field: 'action', title: t('action'), width: 210, slots: { default: 'action' } },
  { field: 'targetType', title: t('target'), width: 180, slots: { default: 'targetType' } },
  { field: 'result', title: t('result'), width: 120, slots: { default: 'result' } },
  { field: 'detail', title: t('description'), minWidth: 280 },
]);
</script>
<template>
  <PaymentLedger
    id="payment-audit"
    :title="t('auditTitle')"
    :rows="logs"
    :columns="columns"
    row-key="id"
    :search-fields="['actorUsername', 'action', 'targetType', 'targetId', 'detail']"
    status-field="result"
    :empty-text="t('noAuditLogs')"
    @refresh="load"
  >
    <template #createdAt="{ row }">{{ formatDate(row.createdAt) }}</template>
    <template #actorUsername="{ row }"
      ><b>{{ row.actorUsername }}</b
      ><small>{{ row.sourceIp }}</small></template
    >
    <template #action="{ row }"
      ><span class="mono">{{ enumLabel(row.action) }}</span></template
    >
    <template #targetType="{ row }">{{ enumLabel(row.targetType) }} {{ row.targetId || '' }}</template>
    <template #result="{ row }"
      ><a-tag :color="row.result === 'SUCCESS' ? 'success' : 'error'">{{ enumLabel(row.result) }}</a-tag></template
    >
  </PaymentLedger>
</template>
