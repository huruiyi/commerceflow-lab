<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Copy, Monitor, Moon, RotateCw, Sun, X } from "@lucide/vue";
import { defaults, themes, type ConsolePreferences } from "./preferences";

const props = defineProps<{ chinese: boolean }>();
const settings = defineModel<ConsolePreferences>({ required: true });
const mode = defineModel<"light" | "dark" | "system">("mode", {
  required: true,
});
const emit = defineEmits<{ close: []; reset: [] }>();
const dialog = ref<HTMLDialogElement>();
const tab = ref("appearance");
const copied = ref(false);
const t = (zh: string, en: string) => (props.chinese ? zh : en);
const tabs = [
  ["appearance", "外观", "Appearance"],
  ["layout", "布局", "Layout"],
  ["keys", "快捷键", "Shortcuts"],
  ["general", "通用", "General"],
  ["widgets", "扩展", "Widgets"],
] as const;
const modes = [
  ["light", "浅色", "Light", Sun],
  ["dark", "深色", "Dark", Moon],
  ["system", "跟随系统", "System", Monitor],
] as const;
onMounted(() => dialog.value?.showModal());

function patch<K extends keyof ConsolePreferences>(
  key: K,
  value: ConsolePreferences[K],
) {
  settings.value = { ...settings.value, [key]: value };
}

async function copy() {
  try {
    await navigator.clipboard.writeText(
      JSON.stringify({ ...settings.value, mode: mode.value }, null, 2),
    );
    copied.value = true;
  } catch {
    copied.value = false;
  }
}
</script>

<template>
  <dialog
    ref="dialog"
    class="sim-preferences"
    :aria-label="t('偏好设置', 'Preferences')"
    @cancel.prevent="emit('close')"
    @click.self="emit('close')"
  >
    <header class="sim-preferences-header">
      <div>
        <strong>{{ t("偏好设置", "Preferences") }}</strong
        ><small>{{
          t("自定义偏好设置与实时预览", "Customize and preview in real time")
        }}</small>
      </div>
      <button
        type="button"
        class="sim-icon"
        :title="t('重置偏好', 'Reset preferences')"
        @click="emit('reset')"
      >
        <RotateCw />
      </button>
      <button
        type="button"
        class="sim-icon"
        :title="t('关闭', 'Close')"
        @click="emit('close')"
      >
        <X />
      </button>
    </header>
    <div class="sim-preferences-body">
      <nav :aria-label="t('偏好分类', 'Preference sections')">
        <button
          v-for="[id, zh, en] in tabs"
          :key="id"
          type="button"
          :aria-pressed="tab === id"
          @click="tab = id"
        >
          {{ t(zh, en) }}
        </button>
      </nav>
      <section v-if="tab === 'appearance'">
        <h3>{{ t("主题", "Theme") }}</h3>
        <div class="sim-preferences-tiles">
          <button
            v-for="[value, zh, en, icon] in modes"
            :key="value"
            type="button"
            :aria-pressed="mode === value"
            @click="mode = value"
          >
            <span><component :is="icon" /></span>{{ t(zh, en) }}
          </button>
        </div>
        <label class="sim-setting"
          >{{ t("深色侧边栏", "Dark sidebar")
          }}<input
            type="checkbox"
            role="switch"
            :checked="settings.darkSidebar"
            @change="
              patch('darkSidebar', ($event.target as HTMLInputElement).checked)
            "
        /></label>
        <label class="sim-setting"
          >{{ t("深色顶栏", "Dark header")
          }}<input
            type="checkbox"
            role="switch"
            :checked="settings.darkHeader"
            @change="
              patch('darkHeader', ($event.target as HTMLInputElement).checked)
            "
        /></label>
        <h3>{{ t("内置主题", "Built-in themes") }}</h3>
        <div class="sim-preferences-tiles">
          <button
            v-for="[value, zh, en, color] in themes"
            :key="value"
            type="button"
            :aria-pressed="settings.theme === value"
            @click="patch('theme', value)"
          >
            <span><i :style="{ backgroundColor: color }" /></span
            >{{ t(zh, en) }}
          </button>
        </div>
        <h3>{{ t("圆角", "Radius") }}</h3>
        <div class="sim-preferences-radius">
          <button
            v-for="value in [0, 0.25, 0.5, 0.75, 1]"
            :key="value"
            type="button"
            :aria-pressed="settings.radius === value"
            @click="patch('radius', value)"
          >
            {{ value }}
          </button>
        </div>
      </section>
      <section v-else-if="tab === 'layout'">
        <label class="sim-setting"
          >{{ t("显示侧栏", "Show sidebar")
          }}<input
            type="checkbox"
            role="switch"
            :checked="settings.showSidebar"
            @change="
              patch('showSidebar', ($event.target as HTMLInputElement).checked)
            "
        /></label>
        <label class="sim-setting"
          >{{ t("紧凑间距", "Compact spacing")
          }}<input
            type="checkbox"
            role="switch"
            :checked="settings.compact"
            @change="
              patch('compact', ($event.target as HTMLInputElement).checked)
            "
        /></label>
      </section>
      <section v-else-if="tab === 'keys'">
        <label class="sim-setting"
          >{{ t("全局搜索 Ctrl K", "Global search Ctrl K")
          }}<input
            type="checkbox"
            role="switch"
            :checked="settings.searchShortcut"
            @change="
              patch(
                'searchShortcut',
                ($event.target as HTMLInputElement).checked,
              )
            "
        /></label>
      </section>
      <section v-else-if="tab === 'general'">
        <label class="sim-setting"
          >{{ t("字体大小", "Font size") }}
          <select
            :value="settings.fontSize"
            @change="
              patch(
                'fontSize',
                Number(($event.target as HTMLSelectElement).value),
              )
            "
          >
            <option
              v-for="value in [12, 13, defaults.fontSize, 15, 16, 18]"
              :key="value"
              :value="value"
            >
              {{ value }} px
            </option>
          </select>
        </label>
      </section>
      <section v-else>
        <label class="sim-setting"
          >{{ t("显示时区选择", "Show time zone")
          }}<input
            type="checkbox"
            role="switch"
            :checked="settings.showTimezone"
            @change="
              patch('showTimezone', ($event.target as HTMLInputElement).checked)
            "
        /></label>
        <label class="sim-setting"
          >{{ t("显示通知", "Show notifications")
          }}<input
            type="checkbox"
            role="switch"
            :checked="settings.showNotifications"
            @change="
              patch(
                'showNotifications',
                ($event.target as HTMLInputElement).checked,
              )
            "
        /></label>
      </section>
    </div>
    <footer>
      <button class="sim-button primary" type="button" @click="copy">
        <Copy />{{
          copied ? t("已复制", "Copied") : t("复制偏好设置", "Copy preferences")
        }}
      </button>
      <button class="sim-button" type="button" @click="emit('reset')">
        {{ t("恢复默认", "Restore defaults") }}
      </button>
    </footer>
  </dialog>
</template>
