<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { api, type Comparison, type Dataset, type Node, type NodePage, type Publication, type Release } from './api';

const props = defineProps<{ panel: string }>();
const datasets = ref<Dataset[]>([]);
const dataset = ref('cn-administrative-divisions');
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
const nodePage = ref<NodePage>();
const ancestors = ref<Node[]>([]);
const text = ref('');
const query = ref('');
const after = ref<string>();
const priorCursors = ref<(string | undefined)[]>([]);
const releaseBefore = ref<number>();
const historyBefore = ref<number>();
const active = computed(() => datasets.value.find((row) => row.datasetKey === dataset.value));
const root = computed(() => '/api/admin/reference-data/datasets/' + dataset.value);
const heading = computed(() => ({ versions: '数据版本', nodes: '记录查询', publications: '发布记录' })[props.panel]);
const sourceLabel = (row: Release) =>
  row.id === active.value?.currentReleaseId ? '当前发布' : row.publishedAt ? '曾发布' : '草稿';
const changeLabel = { ADDED: '新增', CHANGED: '修改', REMOVED: '移除' };

function formatChange(kind: keyof typeof changeLabel) {
  return changeLabel[kind];
}
const releaseColumns = [
  { title: '版本', dataIndex: 'version', key: 'version' },
  { title: '状态', key: 'state', width: 100 },
  { title: '记录数', key: 'rowCount', width: 105 },
  { title: '来源版本', dataIndex: 'sourceRevision', key: 'sourceRevision' },
  { title: '导入时间', key: 'createdAt', width: 175 },
  { title: '操作', key: 'actions', width: 145 },
];
const nodeColumns = [
  { title: '区划或字典名称', dataIndex: 'name', key: 'name' },
  { title: '代码', dataIndex: 'code', key: 'code' },
  { title: '上级代码', dataIndex: 'parentCode', key: 'parentCode' },
  { title: '层级', dataIndex: 'level', width: 70 },
  { title: '类型', dataIndex: ['sourceMetadata', 'type'], key: 'type', width: 100 },
  { title: '操作', key: 'children', width: 110 },
];
const diffColumns = [
  { title: '代码', dataIndex: 'code' },
  { title: '变更', key: 'kind', width: 85 },
  { title: '发布前', key: 'previous' },
  { title: '候选版本', key: 'next' },
];
const historyColumns = [
  { title: '时间', key: 'publishedAt' },
  { title: '原版本 ID', dataIndex: 'previousReleaseId' },
  { title: '发布版本 ID', dataIndex: 'releaseId' },
  { title: '操作人 ID', dataIndex: 'publishedBy' },
];

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

async function fetchVersions() {
  releases.value = await api<Release[]>(
    root.value + '/versions' + (releaseBefore.value ? '?before=' + releaseBefore.value : ''),
  );
}

async function fetchPublications() {
  publications.value = await api<Publication[]>(
    root.value + '/publications' + (historyBefore.value ? '?before=' + historyBefore.value : ''),
  );
}

async function fetchNodes() {
  if (browseId.value == null) {
    nodePage.value = undefined;
    return;
  }
  const params = new URLSearchParams({ size: '25' });
  const parent = ancestors.value.at(-1);
  if (parent) params.set('parentCode', parent.code);
  if (query.value) params.set('q', query.value);
  if (after.value) params.set('after', after.value);
  nodePage.value = await api<NodePage>(root.value + '/versions/' + browseId.value + '/nodes?' + params);
}

function resetCursor() {
  after.value = undefined;
  priorCursors.value = [];
}

async function refreshDataset() {
  feedback.value = '';
  releaseBefore.value = undefined;
  historyBefore.value = undefined;
  ancestors.value = [];
  query.value = '';
  text.value = '';
  resetCursor();
  nodePage.value = undefined;
  browseId.value = undefined;
  await fetchVersions();
  browseId.value = active.value?.currentReleaseId ?? releases.value[0]?.id;
  await fetchPublications();
  if (props.panel === 'nodes') await fetchNodes();
}

onMounted(() =>
  run(async () => {
    datasets.value = await api<Dataset[]>('/api/reference-data/datasets');
    await refreshDataset();
  }),
);
watch(
  () => props.panel,
  (panel) => {
    if (panel === 'nodes' && !nodePage.value) void run(fetchNodes);
  },
);

function changeDataset(key: string) {
  dataset.value = key;
  void run(refreshDataset);
}

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
    releaseBefore.value = undefined;
    feedback.value = '已导入 ' + imported.rowCount.toLocaleString('zh-CN') + ' 条记录';
    await fetchVersions();
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

function selectRelease(id?: number) {
  browseId.value = id;
  ancestors.value = [];
  text.value = '';
  query.value = '';
  resetCursor();
  void run(fetchNodes);
}

function descend(row: Node) {
  ancestors.value = [...ancestors.value, row];
  text.value = '';
  query.value = '';
  resetCursor();
  void run(fetchNodes);
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
  void run(fetchNodes);
}

function search(value: string) {
  query.value = value.trim();
  if (query.value) ancestors.value = [];
  resetCursor();
  void run(fetchNodes);
}

function nextNodes() {
  const cursor = nodePage.value?.nextAfter;
  if (!cursor) return;
  void run(async () => {
    const previous = after.value;
    after.value = cursor;
    try {
      await fetchNodes();
      priorCursors.value.push(previous);
    } catch (reason) {
      after.value = previous;
      throw reason;
    }
  });
}

function previousNodes() {
  if (priorCursors.value.length === 0) return;
  void run(async () => {
    const previous = after.value;
    after.value = priorCursors.value.at(-1);
    try {
      await fetchNodes();
      priorCursors.value.pop();
    } catch (reason) {
      after.value = previous;
      throw reason;
    }
  });
}

function versionPage(before?: number) {
  releaseBefore.value = before;
  void run(fetchVersions);
}

function publicationPage(before?: number) {
  historyBefore.value = before;
  void run(fetchPublications);
}
</script>

<template>
  <section class="reference-workspace">
    <header class="reference-heading">
      <div>
        <h1>{{ heading }}</h1>
        <p v-if="active">{{ active.name }}</p>
      </div>
      <a-select
        aria-label="数据集"
        :value="dataset"
        :disabled="busy || !!review || importing"
        :options="datasets.map((item) => ({ value: item.datasetKey, label: item.name }))"
        @change="changeDataset"
      />
      <a-button v-if="panel === 'versions'" type="primary" :disabled="busy || !active" @click="importing = true">
        导入 CSV
      </a-button>
    </header>

    <a-alert v-if="error" type="error" show-icon :message="error" role="alert" />
    <a-alert v-if="feedback" type="success" show-icon :message="feedback" role="status" />

    <a-card v-if="panel === 'versions'" title="版本清单">
      <a-table
        :columns="releaseColumns"
        :data-source="releases"
        :loading="busy"
        :pagination="false"
        row-key="id"
        :scroll="{ x: 750 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'state'">
            <a-tag :color="record.id === active?.currentReleaseId ? 'green' : record.publishedAt ? 'blue' : 'default'">
              {{ sourceLabel(record) }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'rowCount'">{{ record.rowCount.toLocaleString('zh-CN') }}</template>
          <template v-else-if="column.key === 'createdAt'">{{ formatted(record.createdAt) }}</template>
          <template v-else-if="column.key === 'actions'">
            <a-button size="small" :disabled="busy" @click="compare(record)">查看与比对</a-button>
          </template>
        </template>
      </a-table>
      <a-space v-if="releaseBefore || releases.length === 50" class="reference-controls">
        <a-button :disabled="busy || !releaseBefore" @click="versionPage()">最新版本</a-button>
        <a-button :disabled="busy || releases.length < 50" @click="versionPage(releases.at(-1)?.id)">较早版本</a-button>
      </a-space>
    </a-card>

    <a-card v-else-if="panel === 'nodes'" title="版本记录">
      <div class="reference-controls">
        <a-select
          aria-label="待查询版本"
          placeholder="选择版本"
          :value="browseId"
          :disabled="busy"
          :options="releases.map((item) => ({ value: item.id, label: item.version }))"
          @change="selectRelease"
        />
        <a-input-search
          v-model:value="text"
          placeholder="按名称或代码搜索"
          :disabled="busy || !browseId"
          aria-label="名称或代码"
          enter-button="搜索"
          @search="search"
        />
      </div>
      <div v-if="ancestors.length || query" class="reference-parent-path" aria-label="当前区划路径">
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
      </div>
      <a-table
        :columns="nodeColumns"
        :data-source="nodePage?.rows ?? []"
        :loading="busy"
        :pagination="false"
        row-key="code"
        :scroll="{ x: 570 }"
      >
        <template #bodyCell="{ column, record }">
          <a-button
            v-if="column.key === 'children' && hasChildren(record)"
            size="small"
            :disabled="busy"
            @click="descend(record)"
            >查看下级</a-button
          >
        </template>
      </a-table>
      <a-space v-if="nodePage" class="reference-controls">
        <a-button :disabled="busy || !priorCursors.length" @click="previousNodes">上一页</a-button>
        <a-button :disabled="busy || !nodePage.nextAfter" @click="nextNodes">下一页</a-button>
      </a-space>
    </a-card>

    <a-card v-else title="发布流水">
      <a-table
        :columns="historyColumns"
        :data-source="publications"
        :loading="busy"
        :pagination="false"
        row-key="id"
        :scroll="{ x: 520 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'publishedAt'">{{ formatted(record.publishedAt) }}</template>
        </template>
      </a-table>
      <a-space v-if="historyBefore || publications.length === 50" class="reference-controls">
        <a-button :disabled="busy || !historyBefore" @click="publicationPage()">最新记录</a-button>
        <a-button :disabled="busy || publications.length < 50" @click="publicationPage(publications.at(-1)?.id)">
          较早记录
        </a-button>
      </a-space>
    </a-card>

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
