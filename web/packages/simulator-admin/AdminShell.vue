<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  ArrowLeftToLine,
  ArrowRightLeft,
  ArrowRightToLine,
  ChevronDown,
  ChevronRight,
  ChevronsLeft,
  ChevronsRight,
  ExternalLink,
  FoldHorizontal,
  Fullscreen,
  LayoutGrid,
  Minimize2,
  PanelLeftClose,
  PanelLeftOpen,
  Pin,
  PinOff,
  RotateCw,
  X,
} from "@lucide/vue";
import AdminToolbar from "./AdminToolbar.vue";
import AdminPreferences from "./AdminPreferences.vue";
import VbenLayoutHeader from "./VbenLayoutHeader.vue";
import { menuIcon } from "./icons";
import {
  defaults,
  readMode,
  readPreferences,
  savePreferences,
  themes,
} from "./preferences";
import { setAdminTheme } from "./vxe";

type Item = { key: string; label: string; href?: string; icon?: string };
type Group = { label: string; icon?: string; items: Item[] };
const props = withDefaults(
  defineProps<{
    title: string;
    groups: Group[];
    active: string;
    locale?: "zh-CN" | "en-US";
    account?: string;
    accountDetail?: string;
    accountPage?: string;
    notice?: string;
    busy?: boolean;
    canChangeLanguage?: boolean;
    canLock?: boolean;
  }>(),
  { locale: "zh-CN" },
);
defineSlots<{ default(props: { dark: boolean }): unknown }>();
const emit = defineEmits<{
  navigate: [key: string];
  refresh: [];
  language: [locale: "zh-CN" | "en-US"];
  logout: [];
  lock: [];
}>();

const settings = ref(readPreferences());
const mode = ref(readMode());
const accent = computed(
  () => themes.find(([name]) => name === settings.value.theme)?.[3],
);
const preferencesOpen = ref(false);
const systemTheme = matchMedia("(prefers-color-scheme: dark)");
const systemDark = ref(systemTheme.matches);
const dark = computed(() =>
  mode.value === "system" ? systemDark.value : mode.value === "dark",
);
const sidebarHidden = computed(() => !settings.value.showSidebar);
const sidebarHovering = ref(false);
const contentMaximized = ref(false);
const sidebarRail = computed(
  () => settings.value.sidebarCollapsed || !settings.value.sidebarPinned,
);
const sidebarOverlay = computed(
  () => !settings.value.sidebarPinned && sidebarHovering.value,
);
const sidebarCollapsed = computed(
  () => sidebarRail.value && !sidebarOverlay.value,
);
const openGroups = ref<string[]>([]);
const tabs = ref<string[]>([]);
const pinnedTabs = ref<string[]>([]);
const tabTools = ref<HTMLElement>();
const tabMenuButton = ref<HTMLButtonElement>();
const tabMenuTarget = ref<string>();
const tabMenuOpen = computed(() => tabMenuTarget.value !== undefined);
const contextPosition = ref<{ left: number; top: number }>();
const tabMenuPanel = ref<HTMLElement>();
let tabMenuTrigger: HTMLElement | undefined;
const group = computed(() =>
  props.groups.find(({ items }) =>
    items.some(({ key }) => key === props.active),
  ),
);
const items = computed(() => props.groups.flatMap(({ items }) => items));
const activeItem = computed(() =>
  items.value.find(({ key }) => key === props.active),
);
const openTabs = computed(() =>
  [
    ...pinnedTabs.value,
    ...tabs.value.filter((key) => !pinnedTabs.value.includes(key)),
  ]
    .map((key) => items.value.find((item) => item.key === key))
    .filter((item): item is Item => Boolean(item)),
);
const chinese = computed(() => props.locale === "zh-CN");
const label = (zh: string, en: string) => (chinese.value ? zh : en);
const screenLabel = computed(() =>
  contentMaximized.value
    ? label("还原内容区", "Restore content")
    : label("最大化内容区", "Maximize content"),
);

function onSystemTheme(event: MediaQueryListEvent) {
  systemDark.value = event.matches;
}
function dismissTabMenu(event: PointerEvent) {
  if (!tabTools.value?.contains(event.target as Node))
    tabMenuTarget.value = undefined;
}

onMounted(() => {
  systemTheme.addEventListener("change", onSystemTheme);
  document.addEventListener("pointerdown", dismissTabMenu);
});
onUnmounted(() => {
  systemTheme.removeEventListener("change", onSystemTheme);
  document.removeEventListener("pointerdown", dismissTabMenu);
  setAdminTheme("light");
});
watch(settings, savePreferences);
watch(mode, (value) => localStorage.setItem("sim-admin-theme", value));
watch(dark, (value) => setAdminTheme(value ? "dark" : "light"), {
  immediate: true,
});

function resetPreferences() {
  settings.value = { ...defaults };
  mode.value = "light";
}

function toggleSidebar() {
  sidebarHovering.value = false;
  settings.value = {
    ...settings.value,
    showSidebar: !settings.value.showSidebar,
  };
}

function toggleSidebarCollapse() {
  settings.value = {
    ...settings.value,
    sidebarCollapsed: !sidebarCollapsed.value,
    sidebarPinned: true,
  };
}

function toggleSidebarPin(event: MouseEvent) {
  settings.value = {
    ...settings.value,
    sidebarCollapsed: false,
    sidebarPinned: !settings.value.sidebarPinned,
  };
  sidebarHovering.value = event.detail > 0;
}

watch(
  [() => props.active, group],
  ([key, selectedGroup]) => {
    if (!selectedGroup) return;
    if (!openGroups.value.includes(selectedGroup.label))
      openGroups.value.push(selectedGroup.label);
    if (!tabs.value.includes(key)) tabs.value.push(key);
  },
  { immediate: true },
);

function toggle(label: string) {
  if (sidebarCollapsed.value) {
    toggleSidebarCollapse();
    if (!openGroups.value.includes(label)) openGroups.value.push(label);
    return;
  }
  if (openGroups.value.includes(label))
    openGroups.value = openGroups.value.filter((value) => value !== label);
  else openGroups.value.push(label);
}
function close(key: string) {
  if (tabs.value.length > 1) closeTabs([key]);
}

function closeTabs(keys: string[]) {
  if (props.busy) return;
  const remaining = tabs.value.filter(
    (key) => !keys.includes(key) || pinnedTabs.value.includes(key),
  );
  const next = remaining.at(-1) ?? items.value[0]?.key;
  if (!next) return;
  tabs.value = remaining.length ? remaining : [next];
  if (!tabs.value.includes(props.active)) emit("navigate", next);
}

function toggleTabPin(target: string) {
  pinnedTabs.value = pinnedTabs.value.includes(target)
    ? pinnedTabs.value.filter((key) => key !== target)
    : [...pinnedTabs.value, target];
}

function toggleContent() {
  contentMaximized.value = !contentMaximized.value;
}

const tabMenu = computed(() => {
  const target = tabMenuTarget.value;
  if (target === undefined) return [];
  const keys = openTabs.value.map(({ key }) => key);
  const index = keys.indexOf(target);
  const closable = (keys: string[]) =>
    keys.filter((key) => !pinnedTabs.value.includes(key));
  const left = closable(keys.slice(0, index));
  const right = closable(keys.slice(index + 1));
  const pinned = pinnedTabs.value.includes(target);
  const href = items.value.find(({ key }) => key === target)?.href;
  return [
    {
      icon: X,
      text: label("关闭", "Close"),
      disabled: pinned || keys.length < 2,
      run: () => close(target),
    },
    {
      icon: pinned ? PinOff : Pin,
      text: pinned ? label("取消固定", "Unpin") : label("固定", "Pin"),
      run: () => toggleTabPin(target),
    },
    {
      icon: contentMaximized.value ? Minimize2 : Fullscreen,
      text: contentMaximized.value
        ? label("还原", "Restore")
        : label("最大化", "Maximize"),
      run: () => {
        if (!contentMaximized.value && props.active !== target)
          emit("navigate", target);
        toggleContent();
      },
    },
    {
      icon: RotateCw,
      text: label("重新加载", "Reload"),
      disabled: props.active !== target,
      run: () => emit("refresh"),
    },
    ...(href
      ? [
          {
            icon: ExternalLink,
            text: label("在新窗口打开", "Open in new window"),
            run: () => window.open(href, "_blank", "noopener"),
          },
        ]
      : []),
    {
      icon: ArrowLeftToLine,
      text: label("关闭左侧标签页", "Close tabs to the left"),
      separator: true,
      disabled: !left.length,
      run: () => closeTabs(left),
    },
    {
      icon: ArrowRightToLine,
      text: label("关闭右侧标签页", "Close tabs to the right"),
      disabled: !right.length,
      run: () => closeTabs(right),
    },
    {
      icon: FoldHorizontal,
      text: label("关闭其它标签页", "Close other tabs"),
      separator: true,
      disabled: !left.length && !right.length,
      run: () => closeTabs([...left, ...right]),
    },
    {
      icon: ArrowRightLeft,
      text: label("关闭全部标签页", "Close all tabs"),
      disabled: !closable(keys).length,
      run: () => closeTabs(keys),
    },
  ];
});

function hideTabMenu() {
  if (!tabMenuOpen.value) return;
  tabMenuTarget.value = undefined;
  (tabMenuTrigger ?? tabMenuButton.value)?.focus();
}

function toggleTabMenu() {
  if (tabMenuOpen.value && !contextPosition.value) {
    hideTabMenu();
    return;
  }
  contextPosition.value = undefined;
  tabMenuTrigger = tabMenuButton.value;
  tabMenuTarget.value = props.active;
}

async function openTabContextMenu(event: MouseEvent, target: string) {
  if (props.busy) return;
  const tab = event.currentTarget as HTMLElement;
  tabMenuTrigger = tab.querySelector("button") ?? undefined;
  const bounds = tab.getBoundingClientRect();
  const left = event.clientX || bounds.left;
  const top = event.clientY || bounds.bottom;
  contextPosition.value = { left, top };
  tabMenuTarget.value = target;
  await nextTick();
  const panel = tabMenuPanel.value;
  if (!panel) return;
  contextPosition.value = {
    left: Math.max(
      4,
      Math.min(left, window.innerWidth - panel.offsetWidth - 4),
    ),
    top: Math.max(
      4,
      Math.min(top, window.innerHeight - panel.offsetHeight - 4),
    ),
  };
  panel.querySelector<HTMLButtonElement>("button:not(:disabled)")?.focus();
}
function navigate(event: MouseEvent, item: Item) {
  if (
    item.href &&
    (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey)
  )
    return;
  event.preventDefault();
  if (!props.busy) emit("navigate", item.key);
}
</script>

<template>
  <div
    class="sim-admin"
    :data-theme="settings.theme"
    :style="{
      '--radius': settings.radius + 'rem',
      '--sim-density-font': settings.fontSize + 'px',
      '--sim-theme-color': accent,
    }"
    :class="{
      'sim-nav-hidden': sidebarHidden || contentMaximized,
      'sim-nav-rail': sidebarRail,
      'sim-dark': dark,
      dark,
      'sim-compact': settings.compact,
    }"
  >
    <aside
      v-show="!sidebarHidden && !contentMaximized"
      class="sim-sidebar relative flex min-h-0 flex-col"
      data-layout-region="sidebar"
      :class="{
        dark: settings.darkSidebar,
        'sim-sidebar-collapsed': sidebarCollapsed,
        'sim-sidebar-overlay': sidebarOverlay,
      }"
      @pointerenter="sidebarHovering = $event.pointerType === 'mouse'"
      @pointerleave="sidebarHovering = false"
    >
      <div class="sim-brand">
        <span aria-hidden="true">C</span><strong>{{ title }}</strong>
      </div>
      <nav
        class="min-h-0 flex-1 overflow-y-auto"
        :aria-label="chinese ? '后台菜单' : 'Console navigation'"
      >
        <div
          v-for="section in groups"
          :key="section.label"
          class="sim-menu-group"
        >
          <button
            type="button"
            class="sim-menu-heading"
            :class="{ 'sim-menu-active': group?.label === section.label }"
            :aria-label="section.label"
            :title="sidebarCollapsed ? section.label : undefined"
            :aria-expanded="
              !sidebarCollapsed && openGroups.includes(section.label)
            "
            @click="toggle(section.label)"
          >
            <component :is="menuIcon(section.icon)" /><span>{{
              section.label
            }}</span>
            <component
              :is="
                openGroups.includes(section.label) ? ChevronDown : ChevronRight
              "
              class="sim-caret"
            />
          </button>
          <div
            v-show="openGroups.includes(section.label)"
            class="sim-menu-items"
          >
            <component
              :is="item.href ? 'a' : 'button'"
              v-for="item in section.items"
              :key="item.key"
              :href="item.href"
              :type="item.href ? undefined : 'button'"
              class="sim-menu-link"
              :aria-current="item.key === active ? 'page' : undefined"
              @click="navigate($event, item)"
            >
              <component :is="menuIcon(item.icon)" />{{ item.label }}
            </component>
          </div>
        </div>
      </nav>
      <div class="sim-sidebar-controls">
        <!-- Controls from Vben Admin layout-sidebar (MIT). -->
        <button
          type="button"
          :title="chinese ? '折叠／展开侧栏' : 'Collapse or expand sidebar'"
          :aria-label="
            chinese ? '折叠／展开侧栏' : 'Collapse or expand sidebar'
          "
          :aria-pressed="sidebarCollapsed"
          data-layout-action="toggle-sidebar-collapse"
          class="absolute bottom-2 left-3 z-10 flex-center cursor-pointer rounded-sm bg-accent p-1 text-foreground/60 hover:bg-accent-hover hover:text-foreground"
          @click.stop="toggleSidebarCollapse"
        >
          <ChevronsRight v-if="sidebarCollapsed" class="size-4" />
          <ChevronsLeft v-else class="size-4" />
        </button>
        <button
          v-if="!sidebarCollapsed"
          type="button"
          :title="chinese ? '固定侧栏' : 'Pin sidebar'"
          :aria-label="chinese ? '固定侧栏' : 'Pin sidebar'"
          :aria-pressed="settings.sidebarPinned"
          data-layout-action="toggle-sidebar-fixed"
          class="absolute right-3 bottom-2 z-10 flex-center cursor-pointer rounded-sm bg-accent p-1.25 text-foreground/60 transition-all duration-300 hover:bg-accent-hover hover:text-foreground"
          @click="toggleSidebarPin"
        >
          <Pin v-if="settings.sidebarPinned" class="size-3.5" />
          <PinOff v-else class="size-3.5" />
        </button>
      </div>
    </aside>
    <main
      class="sim-workspace relative flex min-h-0 flex-1 flex-col overflow-hidden"
    >
      <VbenLayoutHeader
        v-show="!contentMaximized"
        class="sim-topbar"
        :full-width="false"
        :height="50"
        :is-mobile="false"
        :show="true"
        :sidebar-width="224"
        :theme="dark || settings.darkHeader ? 'dark' : 'light'"
        width="100%"
        :z-index="10"
      >
        <button
          type="button"
          class="sim-icon"
          :title="chinese ? '切换菜单' : 'Toggle menu'"
          :aria-expanded="!sidebarHidden"
          @click="toggleSidebar"
        >
          <PanelLeftOpen v-if="sidebarHidden" /><PanelLeftClose v-else />
        </button>
        <div
          class="sim-breadcrumb"
          :aria-label="chinese ? '当前位置' : 'Current location'"
        >
          <span v-if="group"
            ><component :is="menuIcon(group.icon)" />{{ group.label }}</span
          >
          <ChevronRight
            v-if="group && activeItem"
            class="sim-breadcrumb-divider"
          />
          <strong v-if="activeItem"
            ><component :is="menuIcon(activeItem.icon)" />{{
              activeItem.label
            }}</strong
          >
        </div>
        <AdminToolbar
          :chinese="chinese"
          :dark="dark"
          :items="items"
          :account="account"
          :account-detail="accountDetail"
          :account-page="accountPage"
          :notice="notice"
          :busy="busy"
          :can-change-language="canChangeLanguage"
          :can-lock="canLock"
          :search-shortcut="settings.searchShortcut"
          :show-timezone="settings.showTimezone"
          :show-notifications="settings.showNotifications"
          @preferences="preferencesOpen = true"
          @navigate="emit('navigate', $event)"
          @theme="mode = dark ? 'light' : 'dark'"
          @language="emit('language', $event)"
          @refresh="emit('refresh')"
          @logout="emit('logout')"
          @lock="emit('lock')"
        />
      </VbenLayoutHeader>
      <div v-if="openTabs.length" class="sim-tabbar">
        <nav
          class="sim-tabs"
          :aria-label="chinese ? '打开的页面' : 'Open pages'"
        >
          <div
            v-for="item in openTabs"
            :key="item.key"
            class="sim-tab"
            :class="{ active: active === item.key }"
            @contextmenu.prevent="openTabContextMenu($event, item.key)"
          >
            <button
              type="button"
              :disabled="busy"
              :aria-current="active === item.key ? 'page' : undefined"
              @click="emit('navigate', item.key)"
            >
              <component :is="menuIcon(item.icon)" />{{ item.label }}
            </button>
            <button
              v-if="pinnedTabs.includes(item.key)"
              type="button"
              class="sim-tab-close"
              :aria-label="label('取消固定', 'Unpin') + ' ' + item.label"
              @click="pinnedTabs = pinnedTabs.filter((key) => key !== item.key)"
            >
              <Pin />
            </button>
            <button
              v-else-if="openTabs.length > 1"
              class="sim-tab-close"
              type="button"
              :disabled="busy"
              :aria-label="(chinese ? '关闭 ' : 'Close ') + item.label"
              @click="close(item.key)"
            >
              <X />
            </button>
          </div>
        </nav>
        <div
          ref="tabTools"
          class="relative flex-center h-full"
          @keydown.esc.stop="hideTabMenu"
        >
          <!-- Vben Admin TabsToolMore, TabsToolRefresh and TabsToolScreen (MIT). -->
          <button
            ref="tabMenuButton"
            type="button"
            class="sim-tab-tool flex-center h-full cursor-pointer border-l border-border px-2 text-lg font-semibold text-muted-foreground hover:bg-muted hover:text-foreground"
            :title="label('标签页操作', 'Tab actions')"
            :aria-expanded="tabMenuOpen && !contextPosition"
            :disabled="busy"
            @click="toggleTabMenu"
          >
            <LayoutGrid class="size-4" />
          </button>
          <button
            type="button"
            class="sim-tab-tool flex-center h-full cursor-pointer border-l border-border px-2 text-lg font-semibold text-muted-foreground hover:bg-muted hover:text-foreground"
            :title="label('刷新标签页', 'Refresh tab')"
            :disabled="busy"
            @click="emit('refresh')"
          >
            <RotateCw class="size-4" />
          </button>
          <button
            type="button"
            class="sim-tab-tool flex-center h-full cursor-pointer border-l border-border px-2 text-lg font-semibold text-muted-foreground hover:bg-muted hover:text-foreground"
            :title="screenLabel"
            :aria-pressed="contentMaximized"
            @click="toggleContent"
          >
            <Minimize2 v-if="contentMaximized" class="size-4" /><Fullscreen
              v-else
              class="size-4"
            />
          </button>
          <div
            v-if="tabMenuOpen"
            ref="tabMenuPanel"
            class="sim-header-panel sim-tab-menu"
            :class="{ 'sim-tab-context-menu': contextPosition }"
            :style="
              contextPosition && {
                left: contextPosition.left + 'px',
                top: contextPosition.top + 'px',
              }
            "
          >
            <template v-for="action in tabMenu" :key="action.text">
              <hr
                v-if="'separator' in action && action.separator"
                class="sim-tab-menu-divider"
              />
              <button
                type="button"
                class="sim-menu-action"
                :disabled="'disabled' in action && action.disabled"
                @click="
                  hideTabMenu();
                  action.run();
                "
              >
                <component :is="action.icon" class="size-4" />{{ action.text }}
              </button>
            </template>
          </div>
        </div>
      </div>
      <AdminPreferences
        v-if="preferencesOpen"
        v-model="settings"
        v-model:mode="mode"
        :chinese="chinese"
        @close="preferencesOpen = false"
        @reset="resetPreferences"
      />
      <section
        class="sim-content flex min-h-0 flex-1 flex-col overflow-x-hidden overflow-y-auto p-4"
        data-layout-region="scroll"
      >
        <slot :dark="dark" />
      </section>
    </main>
  </div>
</template>
