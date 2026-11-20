<script setup lang="ts" generic="Row extends object">
import { computed, ref, useSlots, watch } from 'vue';
import type { VxeGridProps } from 'vxe-table';
import AdminGrid from '@commerceflow/simulator-admin/grid';
import { enumLabel, locale, t } from './i18n';

const props = defineProps<{
  id: string;
  title: string;
  rows: Row[];
  columns: VxeGridProps<Row>['columns'];
  rowKey: string;
  searchFields: (keyof Row)[];
  statusField?: keyof Row;
  statusTitle?: string;
  statusLabel?: (value: string) => string;
  emptyText?: string;
  loading?: boolean;
  selectedRowKey?: string | number;
}>();
const emit = defineEmits<{ refresh: []; select: [row: Row] }>();
const slots = useSlots();
const draft = ref('');
const draftStatus = ref('');
const applied = ref('');
const status = ref('');
const page = ref(1);
const size = ref(20);
const statusOptions = computed(() => {
  const field = props.statusField;
  return field === undefined ? [] : [...new Set(props.rows.map((row) => String(row[field])))].sort();
});
const filtered = computed(() =>
  props.rows.filter(
    (row) =>
      (props.statusField === undefined || !status.value || String(row[props.statusField]) === status.value) &&
      (!applied.value ||
        props.searchFields.some((key) =>
          String(row[key] ?? '')
            .toLowerCase()
            .includes(applied.value),
        )),
  ),
);
watch(
  () => filtered.value.length,
  (count) => {
    page.value = Math.min(page.value, Math.max(1, Math.ceil(count / size.value)));
  },
);

function search() {
  applied.value = draft.value.trim().toLowerCase();
  status.value = draftStatus.value;
  page.value = 1;
}

function reset() {
  draft.value = '';
  draftStatus.value = '';
  search();
}

function changePage(number: number, pageSize: number) {
  page.value = number;
  size.value = pageSize;
}

function choice(value: string) {
  return props.statusLabel?.(value) ?? enumLabel(value);
}
</script>

<template>
  <AdminGrid
    :id="id"
    :title="title"
    :rows="filtered"
    :columns="columns"
    :row-key="rowKey"
    :locale="locale === 'en' ? 'en-US' : 'zh-CN'"
    :page="page"
    :page-size="size"
    :loading="loading"
    :selected-row-key="selectedRowKey"
    :empty-text="emptyText"
    @search="search"
    @reset="reset"
    @page="changePage"
    @refresh="emit('refresh')"
    @select="emit('select', $event)"
  >
    <template #filters>
      <label>{{ locale === 'en' ? 'Keyword' : '关键词' }}<input v-model="draft" /></label>
    </template>
    <template v-if="statusField" #advancedFilters>
      <label>
        {{ statusTitle ?? t('status') }}
        <select v-model="draftStatus">
          <option value="">{{ locale === 'en' ? 'All' : '全部' }}</option>
          <option v-for="value in statusOptions" :key="value" :value="value">{{ choice(value) }}</option>
        </select>
      </label>
    </template>
    <template v-for="name in Object.keys(slots)" #[name]="scope"><slot :name="name" v-bind="scope ?? {}" /></template>
  </AdminGrid>
</template>
