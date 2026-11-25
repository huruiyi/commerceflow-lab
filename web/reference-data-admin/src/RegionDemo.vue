<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { api, type Node, type NodePage, type Release } from './api';

const endpoint = '/api/reference-data/datasets/cn-administrative-divisions';
const levels = ['省级', '地级', '县级', '乡镇街道'];
const release = ref<Release>();
const branches = ref<Node[][]>([]);
const selected = ref<Node[]>([]);
const loading = ref(false);
const error = ref('');
const retryAction = ref<() => void>(() => {});
const current = computed(() => selected.value.at(-1));

function levelName(level: number) {
  return levels[level - 1] ?? '区划';
}

function fieldLabel(nodes: Node[]) {
  return nodes.every((node) => node.level === nodes[0]?.level) ? levelName(nodes[0].level) : '直属下级';
}

async function children(releaseId: number, parent?: string): Promise<Node[]> {
  const rows: Node[] = [];
  let after: string | null = null;
  do {
    const params = new URLSearchParams({ releaseId: String(releaseId), size: '500' });
    if (parent) params.set('parentCode', parent);
    if (after) params.set('after', after);
    const page: NodePage = await api(endpoint + '/nodes?' + params);
    rows.push(...page.rows);
    after = page.nextAfter;
  } while (after);
  return rows;
}

async function run(action: () => Promise<void>) {
  if (loading.value) return;
  loading.value = true;
  error.value = '';
  retryAction.value = () => void run(action);
  try {
    await action();
  } catch (reason) {
    error.value = (reason as Error).message;
  } finally {
    loading.value = false;
  }
}

function reset() {
  void run(async () => {
    selected.value = [];
    branches.value = [];
    release.value = await api<Release>(endpoint + '/versions/current');
    const roots = await children(release.value.id);
    if (roots.length) branches.value = [roots];
  });
}

function choose(index: number, code?: string) {
  void run(async () => {
    selected.value = selected.value.slice(0, index);
    branches.value = branches.value.slice(0, index + 1);
    const node = branches.value[index]?.find((option) => option.code === code);
    if (!node || !release.value) return;
    selected.value.push(node);
    const options = await children(release.value.id, node.code);
    if (options.length) branches.value.push(options);
  });
}

onMounted(reset);
</script>

<template>
  <section class="reference-workspace">
    <header class="reference-heading">
      <div>
        <h1>区划联动</h1>
        <p v-if="release">中国行政区划 · {{ release.version }}</p>
      </div>
      <a-button :disabled="loading" @click="reset">重置</a-button>
    </header>

    <a-card title="选择行政区划">
      <a-alert v-if="error" type="error" show-icon :message="error">
        <template #action>
          <a-button size="small" @click="retryAction">重试</a-button>
        </template>
      </a-alert>
      <div class="region-levels">
        <div v-for="(options, index) in branches" :key="index" class="region-level">
          <label :for="'region-level-' + index">{{ fieldLabel(options) }}</label>
          <a-select
            :id="'region-level-' + index"
            :value="selected[index]?.code"
            :options="options.map((node) => ({ value: node.code, label: node.name }))"
            :disabled="loading"
            :loading="loading && index === branches.length - 1"
            placeholder="请选择"
            option-filter-prop="label"
            show-search
            allow-clear
            @change="choose(index, $event)"
          />
        </div>
      </div>
      <p v-if="loading" role="status">正在加载区划…</p>
      <a-descriptions v-if="current" bordered size="small" :column="1" class="region-result">
        <a-descriptions-item label="已选路径">{{ selected.map((node) => node.name).join(' / ') }}</a-descriptions-item>
        <a-descriptions-item label="区划代码">{{ current.code }}</a-descriptions-item>
        <a-descriptions-item label="层级">{{ levelName(current.level) }}</a-descriptions-item>
      </a-descriptions>
    </a-card>
  </section>
</template>
