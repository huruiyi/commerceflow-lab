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

function resultLabel(value: string) {
  const key = `auditResult.${value}`;
  const label = t(key);
  return label === key ? value : label;
}

const columns = computed<VxeGridProps<AuditLog>['columns']>(() => [
  {
    field: 'createdAt',
    title: t('time'),
    minWidth: 174,
    align: 'left',
    showOverflow: 'title',
    formatter: ({ row }) => formatDate(row.createdAt),
  },
  { field: 'actorUsername', title: t('actor'), minWidth: 125, align: 'left', showOverflow: 'title' },
  { field: 'sourceIp', title: t('sourceIp'), minWidth: 100, align: 'left', showOverflow: 'title' },
  {
    field: 'action',
    title: t('auditAction'),
    minWidth: 125,
    align: 'left',
    formatter: ({ row }) => enumLabel(row.action),
  },
  {
    field: 'targetType',
    title: t('target'),
    minWidth: 215,
    align: 'left',
    showOverflow: 'title',
    formatter: ({ row }) => [enumLabel(row.targetType), row.targetId].filter(Boolean).join(' · '),
  },
  { field: 'result', title: t('result'), minWidth: 86, align: 'left', formatter: ({ row }) => resultLabel(row.result) },
  { field: 'detail', title: t('description'), minWidth: 235, align: 'left', showOverflow: 'title' },
]);
</script>
<template>
  <PaymentLedger
    id="payment-audit"
    :title="t('auditTitle')"
    :rows="logs"
    :columns="columns"
    row-key="id"
    :search-fields="['actorUsername', 'sourceIp', 'action', 'targetType', 'targetId', 'detail']"
    status-field="result"
    :status-title="t('result')"
    :status-label="resultLabel"
    :empty-text="t('noAuditLogs')"
    @refresh="load"
  />
</template>
