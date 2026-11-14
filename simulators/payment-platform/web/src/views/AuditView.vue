<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { api } from '../api';
import { enumLabel, formatDate, t } from '../i18n';
import type { AuditLog } from '../types';

const logs = ref<AuditLog[]>([]);
onMounted(async () => (logs.value = await api('/api/admin/audit-logs')));
</script>
<template>
  <div class="page-title">
    <div>
      <span class="eyebrow">{{ t('securityTrail') }}</span>
      <h1>{{ t('auditTitle') }}</h1>
      <p>{{ t('auditDescription') }}</p>
    </div>
  </div>
  <article class="card">
    <vxe-table :data="logs" border stripe show-overflow height="560" :empty-text="t('noAuditLogs')">
      <vxe-column field="createdAt" :title="t('time')" width="190">
        <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
      </vxe-column>
      <vxe-column field="actorUsername" :title="t('actor')" width="180">
        <template #default="{ row }"
          ><b>{{ row.actorUsername }}</b
          ><small>{{ row.sourceIp }}</small></template
        >
      </vxe-column>
      <vxe-column field="action" :title="t('action')" width="210">
        <template #default="{ row }"
          ><span class="mono">{{ enumLabel(row.action) }}</span></template
        >
      </vxe-column>
      <vxe-column field="targetType" :title="t('target')" width="180">
        <template #default="{ row }">{{ enumLabel(row.targetType) }} {{ row.targetId || '' }}</template>
      </vxe-column>
      <vxe-column field="result" :title="t('result')" width="120">
        <template #default="{ row }">
          <a-tag :color="row.result === 'SUCCESS' ? 'success' : 'error'">{{ enumLabel(row.result) }}</a-tag>
        </template>
      </vxe-column>
      <vxe-column field="detail" :title="t('description')" min-width="280" />
    </vxe-table>
  </article>
</template>
