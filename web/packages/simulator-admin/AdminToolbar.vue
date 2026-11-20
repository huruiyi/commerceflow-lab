<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import {
  ArrowDown,
  ArrowUp,
  Bell,
  CornerDownLeft,
  CircleCheck,
  Clock,
  Globe,
  MailCheck,
  ShieldCheck,
  Languages,
  LogOut,
  LockKeyhole,
  Maximize,
  Minimize,
  Moon,
  RefreshCw,
  Search,
  SearchX,
  Settings,
  Sun,
  UserRound,
  X,
} from "@lucide/vue";
import { menuIcon } from "./icons";
import AdminTimezone from "./AdminTimezone.vue";
import { formatAdminDate } from "./preferences";

const props = defineProps<{
  chinese: boolean;
  dark: boolean;
  items: { key: string; label: string; icon?: string }[];
  account?: string;
  accountDetail?: string;
  accountPage?: string;
  searchShortcut: boolean;
  showTimezone: boolean;
  showNotifications: boolean;
  notice?: string;
  busy?: boolean;
  canChangeLanguage?: boolean;
  canLock?: boolean;
}>();
const emit = defineEmits<{
  navigate: [key: string];
  theme: [];
  preferences: [];
  language: [locale: "zh-CN" | "en-US"];
  refresh: [];
  logout: [];
  lock: [];
}>();

const dialog = ref<HTMLDialogElement>();
const query = ref("");
const panel = ref<"language" | "notifications" | "account">();
const timezoneOpen = ref(false);
const panelRight = ref(0);
const toolbar = ref<HTMLElement>();
const notifications = ref<{ text: string; time: string; read: boolean }[]>([]);
const unread = computed(() => notifications.value.some(({ read }) => !read));
watch(
  () => props.notice,
  (text) => {
    if (text && text !== notifications.value[0]?.text) {
      notifications.value = [
        { text, time: new Date().toISOString(), read: false },
        ...notifications.value,
      ].slice(0, 12);
    }
  },
  { immediate: true },
);
function markAllRead() {
  notifications.value.forEach((item) => {
    item.read = true;
  });
}
const fullscreen = ref(false);
const fullscreenEnabled = document.fullscreenEnabled;
const historyKey = "sim-admin-search-history";
function readHistory(): string[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(historyKey) ?? "[]");
    return Array.isArray(value)
      ? value
          .filter((entry): entry is string => typeof entry === "string")
          .slice(0, 8)
      : [];
  } catch {
    return [];
  }
}
const history = ref(readHistory());
const keyword = computed(() => query.value.trim().toLowerCase());
const results = computed(() => {
  if (!keyword.value) {
    return history.value.flatMap((key) =>
      props.items.filter((item) => item.key === key),
    );
  }
  return props.items.filter(({ label }) => {
    let position = 0;
    for (const character of keyword.value) {
      const match = label.toLowerCase().indexOf(character, position);
      if (match < 0) return false;
      position = match + 1;
    }
    return true;
  });
});
const activeIndex = ref(0);
const activeItem = computed(() => results.value[activeIndex.value]);
watch(keyword, () => {
  activeIndex.value = 0;
});
watch(results, (items) => {
  activeIndex.value = Math.min(
    activeIndex.value,
    Math.max(0, items.length - 1),
  );
});

function saveHistory(keys: string[]) {
  history.value = keys.slice(0, 8);
  try {
    localStorage.setItem(historyKey, JSON.stringify(history.value));
  } catch {
    // Search still works when the browser disables local storage.
  }
}

function moveSelection(direction: number) {
  const length = results.value.length;
  if (!length) return;
  activeIndex.value = (activeIndex.value + direction + length) % length;
  void nextTick(() =>
    document
      .getElementById("sim-search-option-" + activeIndex.value)
      ?.scrollIntoView({ block: "nearest" }),
  );
}
const t = (zh: string, en: string) => (props.chinese ? zh : en);

async function openSearch() {
  if (props.busy || dialog.value?.open) return;
  panel.value = undefined;
  query.value = "";
  activeIndex.value = 0;
  await nextTick();
  dialog.value?.showModal();
}

function selectPage(key?: string) {
  if (!key || props.busy || !props.items.some((item) => item.key === key))
    return;
  saveHistory([key, ...history.value.filter((value) => value !== key)]);
  dialog.value?.close();
  emit("navigate", key);
}

function togglePanel(
  value: NonNullable<typeof panel.value>,
  event: MouseEvent,
) {
  const button = event.currentTarget as HTMLElement;
  panelRight.value =
    value === "language"
      ? Math.max(
          0,
          (toolbar.value?.getBoundingClientRect().right ?? 0) -
            button.getBoundingClientRect().right,
        )
      : 0;
  panel.value = panel.value === value ? undefined : value;
}

function onKey(event: KeyboardEvent) {
  if (
    props.searchShortcut &&
    event.key.toLowerCase() === "k" &&
    (event.ctrlKey || event.metaKey)
  ) {
    event.preventDefault();
    void openSearch();
  }
  if (event.key === "Escape") panel.value = undefined;
}

function dismissPanel(event: PointerEvent) {
  if (
    panel.value &&
    event.target instanceof Node &&
    !toolbar.value?.contains(event.target)
  )
    panel.value = undefined;
}

function onFullscreen() {
  fullscreen.value = Boolean(document.fullscreenElement);
}

async function toggleFullscreen() {
  if (fullscreen.value) await document.exitFullscreen();
  else await document.documentElement.requestFullscreen();
}

onMounted(() => {
  document.addEventListener("keydown", onKey);
  document.addEventListener("pointerdown", dismissPanel);
  document.addEventListener("fullscreenchange", onFullscreen);
  onFullscreen();
});
onUnmounted(() => {
  document.removeEventListener("keydown", onKey);
  document.removeEventListener("pointerdown", dismissPanel);
  document.removeEventListener("fullscreenchange", onFullscreen);
});
</script>

<template>
  <div ref="toolbar" class="sim-top-actions">
    <button
      type="button"
      class="sim-global-search"
      :aria-label="t('搜索页面', 'Search pages')"
      :disabled="busy"
      @click="openSearch"
    >
      <Search /><span>{{ t("搜索", "Search") }}</span
      ><kbd v-if="searchShortcut">Ctrl K</kbd>
    </button>
    <button
      type="button"
      class="sim-icon"
      :title="t('布局设置', 'Layout settings')"
      @click="
        panel = undefined;
        emit('preferences');
      "
    >
      <Settings />
    </button>
    <button
      type="button"
      class="sim-icon"
      :title="dark ? t('切换浅色', 'Light mode') : t('切换深色', 'Dark mode')"
      :aria-pressed="dark"
      @click="emit('theme')"
    >
      <Sun v-if="dark" /><Moon v-else fill="currentColor" />
    </button>
    <button
      v-if="canChangeLanguage"
      type="button"
      class="sim-icon"
      :title="t('切换语言', 'Change language')"
      :aria-expanded="panel === 'language'"
      @click="togglePanel('language', $event)"
    >
      <Languages />
    </button>
    <button
      type="button"
      v-if="showTimezone"
      class="sim-icon sim-timezone"
      :title="t('时区设置', 'Time zone settings')"
      :aria-expanded="timezoneOpen"
      aria-haspopup="dialog"
      @click="
        panel = undefined;
        timezoneOpen = true;
      "
    >
      <Clock /><Globe class="sim-clock-globe" />
    </button>
    <button
      v-if="fullscreenEnabled"
      type="button"
      class="sim-icon sim-fullscreen"
      :title="
        fullscreen
          ? t('退出全屏', 'Exit full screen')
          : t('全屏', 'Full screen')
      "
      @click="toggleFullscreen"
    >
      <Minimize v-if="fullscreen" /><Maximize v-else />
    </button>
    <button
      type="button"
      class="sim-icon"
      :title="t('刷新当前页面', 'Refresh page')"
      :disabled="busy"
      @click="emit('refresh')"
    >
      <RefreshCw />
    </button>
    <button
      type="button"
      class="sim-icon"
      v-if="showNotifications"
      :title="t('通知', 'Notifications')"
      :aria-expanded="panel === 'notifications'"
      @click="togglePanel('notifications', $event)"
    >
      <Bell /><i v-if="unread" class="sim-notice-dot" aria-hidden="true"></i>
    </button>
    <button
      v-if="canLock"
      type="button"
      class="sim-icon"
      :title="t('锁定后台', 'Lock console')"
      :disabled="busy"
      @click="emit('lock')"
    >
      <LockKeyhole />
    </button>
    <button
      type="button"
      class="sim-icon sim-logout"
      :title="t('退出登录', 'Sign out')"
      :disabled="busy"
      @click="emit('logout')"
    >
      <LogOut />
    </button>
    <button
      type="button"
      class="sim-avatar"
      :title="t('当前账号', 'Current account')"
      :aria-expanded="panel === 'account'"
      @click="togglePanel('account', $event)"
    >
      <span v-if="account" aria-hidden="true">{{
        account.slice(0, 1)
      }}</span><UserRound v-else /><i
        class="sim-avatar-dot"
        aria-hidden="true"
      ></i>
    </button>

    <section
      v-if="panel"
      class="sim-header-panel"
      :class="'sim-header-' + panel"
      :style="{ right: panelRight + 'px' }"
    >
      <template v-if="panel === 'language'">
        <button
          v-for="[value, label, current] in [
            ['zh-CN', '简体中文', chinese],
            ['en-US', 'English', !chinese],
          ]"
          :key="String(value)"
          type="button"
          class="sim-menu-action"
          :aria-pressed="Boolean(current)"
          @click="
            emit('language', value as 'zh-CN' | 'en-US');
            panel = undefined;
          "
        >
          <span aria-hidden="true" class="sim-choice-dot">{{
            current ? "●" : ""
          }}</span>{{ label }}
        </button>
      </template>
      <template v-else-if="panel === 'notifications'">
        <header>
          <strong>{{ t("通知", "Notifications") }}</strong>
          <button
            type="button"
            class="sim-icon"
            :title="t('全部标为已读', 'Mark all as read')"
            :disabled="!unread"
            @click="markAllRead"
          >
            <MailCheck />
          </button>
        </header>
        <ul v-if="notifications.length" class="sim-notification-list">
          <li v-for="item in notifications" :key="item.time + item.text">
            <span class="sim-notification-icon"><Bell /></span>
            <div>
              <strong>{{ t("系统提示", "System notice") }}</strong>
              <p>{{ item.text }}</p>
              <small>{{
                formatAdminDate(item.time, chinese ? "zh-CN" : "en-US")
              }}</small>
            </div>
            <button
              type="button"
              class="sim-icon"
              :title="t('标为已读', 'Mark as read')"
              :disabled="item.read"
              @click="item.read = true"
            >
              <CircleCheck />
            </button>
          </li>
        </ul>
        <p v-else class="sim-notifications-empty">
          {{ t("暂无通知", "No notifications") }}
        </p>
        <footer>
          <button
            type="button"
            class="sim-button"
            :disabled="!notifications.length"
            @click="notifications = []"
          >
            {{ t("清空", "Clear") }}
          </button>
        </footer>
      </template>
      <template v-else>
        <div class="sim-account-identity">
          <span class="sim-avatar sim-account-photo"
            >{{ account?.slice(0, 1) }}<UserRound v-if="!account"
          /></span>
          <div>
            <strong>{{
              account || t("模拟器管理员", "Simulator administrator")
            }}</strong><small v-if="accountDetail">{{ accountDetail }}</small>
          </div>
        </div>
        <button
          v-if="accountPage"
          type="button"
          class="sim-menu-action"
          @click="
            emit('navigate', accountPage);
            panel = undefined;
          "
        >
          <ShieldCheck />{{ t("安全中心", "Security center") }}
        </button>
        <button
          type="button"
          class="sim-menu-action"
          :disabled="busy"
          @click="emit('logout')"
        >
          <LogOut />{{ t("退出登录", "Sign out") }}
        </button>
      </template>
    </section>

    <AdminTimezone
      v-if="timezoneOpen"
      :chinese="chinese"
      @close="timezoneOpen = false"
    />
    <dialog
      ref="dialog"
      class="sim-command-dialog"
      :aria-label="t('搜索页面', 'Search pages')"
      @click.self="dialog?.close()"
    >
      <form
        class="sim-command-input"
        @submit.prevent="selectPage(activeItem?.key)"
      >
        <Search aria-hidden="true" />
        <input
          v-model="query"
          autofocus
          role="combobox"
          aria-autocomplete="list"
          aria-expanded="true"
          aria-controls="sim-search-results"
          :aria-activedescendant="
            activeItem ? 'sim-search-option-' + activeIndex : undefined
          "
          :placeholder="t('搜索导航菜单', 'Search navigation menu')"
          :aria-label="t('搜索菜单', 'Search menu')"
          @keydown.down.prevent="moveSelection(1)"
          @keydown.up.prevent="moveSelection(-1)"
        />
        <button
          type="button"
          class="sim-icon"
          :title="t('关闭搜索', 'Close search')"
          @click="dialog?.close()"
        >
          <X />
        </button>
      </form>
      <div class="sim-command-results">
        <p v-if="!results.length" class="sim-command-empty" role="status">
          <SearchX v-if="keyword" aria-hidden="true" />
          {{
            keyword
              ? t("没有匹配的页面", "No matching pages")
              : t("没有搜索历史", "No recent searches")
          }}
        </p>
        <p v-else-if="!keyword" class="sim-command-history">
          {{ t("搜索历史", "Recent searches") }}
        </p>
        <ul
          id="sim-search-results"
          role="listbox"
          :aria-label="t('导航菜单', 'Navigation menu')"
        >
          <li
            v-for="(item, index) in results"
            :key="item.key"
            class="sim-command-option"
            :class="{ active: index === activeIndex }"
            @pointermove="activeIndex = index"
          >
            <button
              :id="'sim-search-option-' + index"
              type="button"
              role="option"
              :aria-selected="index === activeIndex"
              @click="selectPage(item.key)"
            >
              <component :is="menuIcon(item.icon)" />{{ item.label }}
            </button>
            <button
              v-if="!keyword"
              type="button"
              class="sim-command-remove"
              :aria-label="t('移除历史 ', 'Remove recent ') + item.label"
              @click="saveHistory(history.filter((key) => key !== item.key))"
            >
              <X />
            </button>
          </li>
        </ul>
      </div>
      <footer class="sim-command-footer">
        <span
          ><CornerDownLeft aria-hidden="true" />{{ t("选择", "Select") }}</span
        >
        <span
          ><ArrowUp aria-hidden="true" /><ArrowDown aria-hidden="true" />{{
            t("导航", "Navigate")
          }}</span
        >
        <span><kbd>Esc</kbd>{{ t("关闭", "Close") }}</span>
      </footer>
    </dialog>
  </div>
</template>
