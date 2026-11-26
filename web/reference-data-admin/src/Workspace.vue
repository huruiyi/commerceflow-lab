<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import AdminGrid from '@commerceflow/simulator-admin/grid';
import { api, type Comparison, type Dataset, type Node, type NodePage, type Publication, type Release } from './api';

const props = defineProps<{ panel: string }>();
const defaultDataset = 'cn-administrative-divisions';
const datasets = ref<Dataset[]>([]);
const dataset = ref(defaultDataset);
const draftDataset = ref(defaultDataset);
const releases = ref<Release[]>([]);
const publications = ref<Publication[]>([]);
const busy = ref(false);
const error = ref('');
const feedback = ref('');
const importing = ref(false);
const picked = ref<File>();
const version = ref('');
const sourceUrl = ref('');
const sourceRevision = ref('');
const review = ref<{ release: Release; diff: Comparison }>();
const browseId = ref<number>();
const draftBrowseId = ref<number>();
const nodePage = ref<NodePage>();
const ancestors = ref<Node[]>([]);
const text = ref('');
const query = ref('');
type Cursor = string | number | undefined;
const pageCursor = ref<Cursor>();
const priorCursors = ref<Cursor[]>([]);
const atEnd = ref(false);
const active = computed(() => datasets.value.find((row) => row.datasetKey === dataset.value));
const root = computed(() => '/api/admin/reference-data/datasets/' + dataset.value);
const nextCursor = computed(() => {
  if (atEnd.value) return undefined;
  if (props.panel === 'nodes') return nodePage.value?.nextAfter ?? undefined;
  const rows = props.panel === 'versions' ? releases.value : publications.value;
  return rows.length === 50 ? rows.at(-1)?.id : undefined;
});
const rows = computed<(Release | Node | Publication)[]>(() =>
  props.panel === 'nodes'
    ? (nodePage.value?.rows ?? [])
    : props.panel === 'versions'
      ? releases.value
      : publications.value,
);
const sourceLabel = (row: Release) =>
  row.id === active.value?.currentReleaseId ? '当前发布' : row.publishedAt ? '曾发布' : '草稿';
const changeLabel = { ADDED: '新增', CHANGED: '修改', REMOVED: '移除' };

function formatChange(kind: keyof typeof changeLabel) {
  return changeLabel[kind];
}
const releaseColumns = [
  { title: '版本', field: 'version', minWidth: 150 },
  { title: '状态', field: 'state', width: 100, slots: { default: 'state' } },
  { title: '记录数', field: 'rowCount', width: 100, slots: { default: 'rowCount' } },
  { title: '来源版本', field: 'sourceRevision', minWidth: 185 },
  { title: '导入时间', field: 'createdAt', width: 165, slots: { default: 'createdAt' } },
  { title: '操作', field: 'actions', width: 125, slots: { default: 'actionsCell' } },
];
const nodeColumns = [
  { title: '区划或字典名称', field: 'name', minWidth: 155 },
  { title: '代码', field: 'code', minWidth: 110 },
  { title: '上级代码', field: 'parentCode', minWidth: 110 },
  { title: '层级', field: 'level', width: 70 },
  { title: '类型', field: 'sourceMetadata.type', width: 100 },
  { title: '操作', field: 'children', width: 105, slots: { default: 'children' } },
];
const diffColumns = [
  { title: '代码', dataIndex: 'code' },
  { title: '变更', key: 'kind', width: 85 },
  { title: '发布前', key: 'previous' },
  { title: '候选版本', key: 'next' },
];
const historyColumns = [
  { title: '时间', field: 'publishedAt', minWidth: 165, slots: { default: 'publishedAt' } },
  { title: '原版本 ID', field: 'previousReleaseId', minWidth: 100 },
  { title: '发布版本 ID', field: 'releaseId', minWidth: 110 },
  { title: '操作人 ID', field: 'publishedBy', minWidth: 100 },
];
const tables = {
  versions: { title: '版本清单', columns: releaseColumns, rowKey: 'id' },
  nodes: { title: '版本记录', columns: nodeColumns, rowKey: 'code' },
  publications: { title: '发布流水', columns: historyColumns, rowKey: 'id' },
};
const table = computed(() => tables[props.panel as keyof typeof tables]);

function formatted(value: string) {
  return new Date(value).toLocaleString('zh-CN', { hour12: false });
}

async function run(task: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = '';
  try {
    await task();
  } catch (reason) {
    error.value = (reason as Error).message;
  } finally {
    busy.value = false;
  }
}

async function fetchNodes(cursor: Cursor) {
  if (browseId.value == null) {
    return undefined;
  }
  const params = new URLSearchParams({ size: '25' });
  const parent = ancestors.value.at(-1);
  if (parent) params.set('parentCode', parent.code);
  if (query.value) params.set('q', query.value);
  if (cursor !== undefined) params.set('after', String(cursor));
  return api<NodePage>(root.value + '/versions/' + browseId.value + '/nodes?' + params);
}

async function loadPage(cursor?: Cursor, advancing = false) {
  const before = cursor === undefined ? '' : '?before=' + cursor;
  if (props.panel === 'versions') {
    const rows = await api<Release[]>(root.value + '/versions' + before);
    if (advancing && !rows.length) return false;
    releases.value = rows;
  } else if (props.panel === 'publications') {
    const rows = await api<Publication[]>(root.value + '/publications' + before);
    if (advancing && !rows.length) return false;
    publications.value = rows;
  } else if (props.panel === 'nodes') {
    const page = await fetchNodes(cursor);
    if (advancing && !page?.rows.length) return false;
    nodePage.value = page;
  }
  pageCursor.value = cursor;
  atEnd.value = false;
  return true;
}

function resetCursor() {
  pageCursor.value = undefined;
  priorCursors.value = [];
  atEnd.value = false;
}

async function refreshDataset() {
  feedback.value = '';
  ancestors.value = [];
  query.value = '';
  text.value = '';
  resetCursor();
  nodePage.value = undefined;
  browseId.value = undefined;
  if (props.panel === 'nodes') {
    releases.value = await api<Release[]>(root.value + '/versions');
    browseId.value = active.value?.currentReleaseId ?? releases.value[0]?.id;
    draftBrowseId.value = browseId.value;
  }
  await loadPage();
}

onMounted(() =>
  run(async () => {
    datasets.value = await api<Dataset[]>('/api/reference-data/datasets');
    await refreshDataset();
  }),
);

function chooseFile(event: Event) {
  picked.value = (event.target as HTMLInputElement).files?.[0];
}

function importVersion() {
  if (!picked.value) return;
  const file = picked.value;
  void run(async () => {
    const payload = new FormData();
    payload.set('file', file);
    payload.set('version', version.value.trim());
    payload.set('sourceUrl', sourceUrl.value.trim());
    payload.set('sourceRevision', sourceRevision.value.trim());
    const imported = await api<Release>(root.value + '/versions', { method: 'POST', body: payload });
    importing.value = false;
    picked.value = undefined;
    version.value = '';
    resetCursor();
    feedback.value = '已导入 ' + imported.rowCount.toLocaleString('zh-CN') + ' 条记录';
    await loadPage();
    review.value = {
      release: imported,
      diff: await api<Comparison>(root.value + '/versions/' + imported.id + '/comparison'),
    };
  });
}

function compare(release: Release) {
  void run(async () => {
    review.value = { release, diff: await api<Comparison>(root.value + '/versions/' + release.id + '/comparison') };
  });
}

function publish() {
  const candidate = review.value;
  if (!candidate) return;
  void run(async () => {
    await api<Release>(root.value + '/versions/' + candidate.release.id + '/publish', {
      method: 'POST',
      body: JSON.stringify({ expectedCurrentReleaseId: candidate.diff.currentReleaseId }),
    });
    review.value = undefined;
    datasets.value = await api<Dataset[]>('/api/reference-data/datasets');
    await refreshDataset();
    feedback.value = '已发布 ' + candidate.release.version;
  });
}

function descend(row: Node) {
  ancestors.value = [...ancestors.value, row];
  text.value = '';
  query.value = '';
  resetCursor();
  refreshPage();
}

function hasChildren(row: Node) {
  const count = row.sourceMetadata?.childrenCount;
  return count == null ? row.level < 5 : count > 0;
}

function goToParent(length: number) {
  ancestors.value = ancestors.value.slice(0, length);
  text.value = '';
  query.value = '';
  resetCursor();
  refreshPage();
}

function search() {
  void run(async () => {
    if (dataset.value !== draftDataset.value) {
      dataset.value = draftDataset.value;
      await refreshDataset();
      return;
    }
    if (browseId.value !== draftBrowseId.value || text.value.trim()) ancestors.value = [];
    browseId.value = draftBrowseId.value;
    query.value = text.value.trim();
    resetCursor();
    await loadPage();
  });
}

function resetSearch() {
  draftDataset.value = defaultDataset;
  void run(async () => {
    dataset.value = defaultDataset;
    await refreshDataset();
  });
}

function refreshPage() {
  void run(async () => {
    await loadPage(pageCursor.value);
  });
}

function nextPage() {
  const cursor = nextCursor.value;
  if (cursor === undefined) return;
  void run(async () => {
    const previous = pageCursor.value;
    if (await loadPage(cursor, true)) priorCursors.value.push(previous);
    else atEnd.value = true;
  });
}

function previousPage() {
  if (!priorCursors.value.length) return;
  void run(async () => {
    await loadPage(priorCursors.value.at(-1));
    priorCursors.value.pop();
  });
}
</script>

<template>
  <section class="reference-workspace" :class="{ 'reference-list': table }">
    <a-alert v-if="error" type="error" show-icon :message="error" role="alert" />
    <a-alert v-if="feedback" type="success" show-icon :message="feedback" role="status" />

    <template v-if="!table">
      <header class="reference-heading"><h1>数据总览</h1></header>
      <a-spin :spinning="busy">
        <div class="reference-datasets">
          <a-card v-for="item in datasets" :key="item.datasetKey" :title="item.name">
            <p>{{ item.currentReleaseId == null ? '待发布' : '当前发布版本 ID · ' + item.currentReleaseId }}</p>
            <a href="#versions">查看数据版本</a>
          </a-card>
        </div>
      </a-spin>
    </template>

    <AdminGrid
      v-else
      :id="'reference-' + panel"
      :title="table.title"
      :rows="rows"
      :columns="table.columns"
      :row-key="table.rowKey"
      :loading="busy"
      @search="search"
      @reset="resetSearch"
      @refresh="refreshPage"
    >
      <template #filters>
        <label>
          数据集
          <select v-model="draftDataset" :disabled="busy">
            <option v-for="item in datasets" :key="item.datasetKey" :value="item.datasetKey">{{ item.name }}</option>
          </select>
        </label>
        <template v-if="panel === 'nodes'">
          <label>
            待查询版本
            <select v-model="draftBrowseId" :disabled="busy || draftDataset !== dataset">
              <option v-if="!releases.length && !browseId" disabled :value="undefined">暂无版本</option>
              <option v-for="item in releases" :key="item.id" :value="item.id">{{ item.version }}</option>
              <option v-if="browseId && !releases.some((item) => item.id === browseId)" :value="browseId">
                当前版本 {{ browseId }}
              </option>
            </select>
          </label>
          <label>
            名称或代码
            <input v-model="text" :disabled="busy" placeholder="输入名称或代码" />
          </label>
        </template>
        <nav
          v-if="panel === 'nodes' && (ancestors.length || query)"
          class="reference-parent-path"
          aria-label="当前区划路径"
        >
          <a-button size="small" :disabled="busy" @click="goToParent(0)">根节点</a-button>
          <a-button
            v-for="(entry, index) in ancestors"
            :key="entry.code"
            size="small"
            :disabled="busy"
            @click="goToParent(index + 1)"
          >
            {{ entry.name }}
          </a-button>
        </nav>
      </template>
      <template #actions>
        <button
          v-if="panel === 'versions'"
          class="sim-button primary"
          type="button"
          :disabled="busy || !active"
          @click="importing = true"
        >
          导入 CSV
        </button>
      </template>
      <template #state="{ row }">
        <a-tag :color="row.id === active?.currentReleaseId ? 'green' : row.publishedAt ? 'blue' : 'default'">
          {{ sourceLabel(row) }}
        </a-tag>
      </template>
      <template #rowCount="{ row }">{{ row.rowCount.toLocaleString('zh-CN') }}</template>
      <template #createdAt="{ row }">{{ formatted(row.createdAt) }}</template>
      <template #publishedAt="{ row }">{{ formatted(row.publishedAt) }}</template>
      <template #actionsCell="{ row }">
        <a-button size="small" :disabled="busy" @click="compare(row)">查看与比对</a-button>
      </template>
      <template #children="{ row }">
        <a-button v-if="hasChildren(row)" size="small" :disabled="busy" @click="descend(row)">查看下级</a-button>
      </template>
      <template #pagination>
        <nav class="reference-pagination" aria-label="列表分页">
          <span aria-live="polite">第 {{ priorCursors.length + 1 }} 页 · 本页 {{ rows.length }} 条</span>
          <button class="sim-button" type="button" :disabled="busy || !priorCursors.length" @click="previousPage">
            上一页
          </button>
          <button class="sim-button" type="button" :disabled="busy || nextCursor === undefined" @click="nextPage">
            下一页
          </button>
        </nav>
      </template>
    </AdminGrid>

    <a-modal
      v-model:open="importing"
      title="导入数据版本"
      ok-text="导入草稿"
      cancel-text="取消"
      :confirm-loading="busy"
      :closable="!busy"
      :mask-closable="!busy"
      :ok-button-props="{ disabled: !picked || !version.trim() || !sourceUrl.trim() || !sourceRevision.trim() }"
      @ok="importVersion"
    >
      <a-alert v-if="error" type="error" :message="error" show-icon />
      <a-form layout="vertical" class="reference-form">
        <a-form-item label="版本名称" html-for="reference-version">
          <a-input
            id="reference-version"
            v-model:value="version"
            placeholder="例如 nbs-2023-06-30"
            maxlength="64"
            :disabled="busy"
          />
        </a-form-item>
        <a-form-item label="来源链接" html-for="reference-source">
          <a-input
            id="reference-source"
            v-model:value="sourceUrl"
            placeholder="https://…"
            :disabled="busy"
            maxlength="500"
          />
        </a-form-item>
        <a-form-item label="来源版本" html-for="reference-revision">
          <a-input
            id="reference-revision"
            v-model:value="sourceRevision"
            placeholder="发布日期或源提交号"
            :disabled="busy"
            maxlength="160"
          />
        </a-form-item>
        <a-form-item label="UTF-8 CSV" html-for="reference-file">
          <input
            id="reference-file"
            class="reference-file"
            type="file"
            accept=".csv,text/csv"
            :disabled="busy"
            @change="chooseFile"
          />
          <small>表头为 code,parent_code,level,name，可附加 source_metadata。根节点上级代码留空。</small>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal
      :open="Boolean(review)"
      title="版本比较"
      width="min(840px, 95vw)"
      ok-text="发布此版本"
      cancel-text="关闭"
      :confirm-loading="busy"
      :ok-button-props="{ disabled: review?.release.id === active?.currentReleaseId }"
      @ok="publish"
      @cancel="review = undefined"
    >
      <template v-if="review">
        <a-alert v-if="error" type="error" :message="error" show-icon />
        <p>{{ review.release.version }} · {{ review.release.rowCount.toLocaleString('zh-CN') }} 条</p>
        <p>
          {{ review.release.sourceRevision }} ·
          <a :href="review.release.sourceUrl" target="_blank" rel="noopener noreferrer">查看来源</a>
        </p>
        <p class="reference-checksum">SHA-256 {{ review.release.sha256 }}</p>
        <div class="reference-stats">
          <a-tag color="green">新增 {{ review.diff.counts.added }}</a-tag>
          <a-tag color="blue">修改 {{ review.diff.counts.changed }}</a-tag>
          <a-tag color="red">移除 {{ review.diff.counts.removed }}</a-tag>
        </div>
        <a-table
          :columns="diffColumns"
          :data-source="review.diff.sample"
          :pagination="false"
          row-key="code"
          size="small"
          :scroll="{ x: 540, y: 340 }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'kind'">{{ formatChange(record.kind) }}</template>
            <template v-if="column.key === 'previous'">
              {{ record.previousName ? record.previousName + ' / ' + (record.previousParentCode ?? '根节点') : '—' }}
            </template>
            <template v-if="column.key === 'next'">
              {{ record.nextName ? record.nextName + ' / ' + (record.nextParentCode ?? '根节点') : '—' }}
            </template>
          </template>
        </a-table>
      </template>
    </a-modal>
  </section>
</template>
