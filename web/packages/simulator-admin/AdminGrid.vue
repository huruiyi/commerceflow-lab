<script setup lang="ts" generic="Row">
/* Search tool, toolbar and pagination defaults adapted from vue-vben-admin (MIT). See LICENSE.vben. */
import { computed, onMounted, onUnmounted, ref, useSlots } from "vue";
import { VxeGrid, type VxeGridEvents, type VxeGridProps } from "vxe-table";

const props = withDefaults(
  defineProps<{
    id: string;
    title: string;
    rows: Row[];
    columns: VxeGridProps<Row>["columns"];
    rowKey: string;
    loading?: boolean;
    total?: number;
    page?: number;
    pageSize?: number;
    fixedPageSize?: boolean;
    locale?: "zh-CN" | "en-US";
    emptyText?: string;
  }>(),
  { locale: "zh-CN", page: 1, pageSize: 20 },
);

const emit = defineEmits<{
  search: [];
  reset: [];
  refresh: [];
  page: [page: number, size: number];
}>();

const slots = useSlots();
const expanded = ref(true);
const mobileBreakpoint = window.matchMedia("(width < 768px)");
const smallViewport = ref(mobileBreakpoint.matches);
function updateViewport(event: MediaQueryListEvent) {
  smallViewport.value = event.matches;
}
onMounted(() => mobileBreakpoint.addEventListener("change", updateViewport));
onUnmounted(() =>
  mobileBreakpoint.removeEventListener("change", updateViewport),
);
const chinese = computed(() => props.locale === "zh-CN");
const toolbar = computed<VxeGridProps<Row>["toolbarConfig"]>(() => ({
  custom: true,
  zoom: true,
  refresh: { queryMethod: async () => emit("refresh") },
  tools: slots.advancedFilters
    ? [
        {
          code: "search",
          icon: "vxe-icon-search",
          circle: true,
          status: expanded.value ? "primary" : undefined,
          title: chinese.value ? "切换搜索区" : "Toggle search",
        },
      ]
    : [],
  slots: { buttons: "heading", toolPrefix: "toolbarActions" },
}));

const mobileLayouts = [
  "PrevJump",
  "PrevPage",
  "Number",
  "NextPage",
  "NextJump",
] as const;
const pager = computed<VxeGridProps<Row>["pagerConfig"]>(() => ({
  background: true,
  total: props.total ?? props.rows.length,
  currentPage: props.page,
  pageSize: props.pageSize,
  size: "mini",
  className: "mt-2 w-full",
  pageSizes: [10, 20, 30, 50, 100, 200],
  layouts: smallViewport.value
    ? [...mobileLayouts]
    : [
        "Total",
        ...(props.fixedPageSize ? [] : (["Sizes"] as const)),
        "Home",
        ...mobileLayouts,
        "End",
      ],
}));

const rows = computed(() =>
  props.total === undefined
    ? props.rows.slice(
        (props.page - 1) * props.pageSize,
        props.page * props.pageSize,
      )
    : props.rows,
);
const pagerChanged: VxeGridEvents.PageChange<Row> = ({
  currentPage,
  pageSize,
}) => {
  emit("page", currentPage, pageSize);
};
const forwarded = computed(() =>
  Object.keys(slots).filter(
    (name) => !["filters", "advancedFilters", "actions"].includes(name),
  ),
);
</script>

<template>
  <!-- Vben Page auto-content-height layout, with the search form above the grid. -->
  <div
    class="sim-grid relative flex h-full min-h-0 flex-1 flex-col overflow-hidden"
  >
    <form
      v-if="$slots.filters"
      class="sim-search"
      @submit.prevent="emit('search')"
    >
      <div class="sim-search-fields">
        <slot name="filters" />
        <div
          v-if="$slots.advancedFilters"
          :id="id + '-advanced'"
          v-show="expanded"
          class="sim-search-advanced"
        >
          <slot name="advancedFilters" />
        </div>
      </div>
      <div class="sim-search-actions">
        <button type="button" class="sim-button" @click="emit('reset')">
          {{ chinese ? "重置" : "Reset" }}
        </button>
        <button type="submit" class="sim-button primary">
          {{ chinese ? "搜索" : "Search" }}
        </button>
        <button
          type="button"
          class="sim-text-button"
          v-if="$slots.advancedFilters"
          :aria-controls="id + '-advanced'"
          :aria-expanded="expanded"
          @click="expanded = !expanded"
        >
          {{
            expanded
              ? chinese
                ? "收起 ⌃"
                : "Collapse ⌃"
              : chinese
                ? "展开 ⌄"
                : "Expand ⌄"
          }}
        </button>
      </div>
    </form>
    <section
      class="min-h-0 flex-1 overflow-y-auto"
      data-layout-region="page-content"
      style="contain: size"
      :aria-label="title"
    >
      <div class="h-full rounded-md bg-card">
        <VxeGrid
          :id="id"
          class="p-2 pt-0"
          data-vben-grid
          :data="rows"
          :columns="columns"
          height="auto"
          auto-resize
          :loading="loading"
          :empty-text="emptyText"
          :row-config="{ keyField: rowKey }"
          :toolbar-config="toolbar"
          :pager-config="pager"
          :custom-config="{ storage: Boolean(id) }"
          align="center"
          show-overflow="tooltip"
          show-header-overflow
          @page-change="pagerChanged"
          @toolbar-tool-click="
            ({ code }) => {
              if (code === 'search') expanded = !expanded;
            }
          "
        >
          <template #heading
            ><h2 class="sim-grid-title">{{ title }}</h2></template
          >
          <template #toolbarActions><slot name="actions" /></template>
          <template v-for="name in forwarded" #[name]="scope"
            ><slot :name="name" v-bind="scope ?? {}"
          /></template>
        </VxeGrid>
      </div>
    </section>
  </div>
</template>
