<script setup lang="ts">
/* Timezone dialog adapted from vue-vben-admin (MIT). See LICENSE.vben. */
import { onMounted, ref } from "vue";
import { X } from "@lucide/vue";
import { setTimezone, timezone, timezones } from "./preferences";

const props = defineProps<{ chinese: boolean }>();
const emit = defineEmits<{ close: [] }>();
const dialog = ref<HTMLDialogElement>();
const draft = ref(timezone.value);
const t = (zh: string, en: string) => (props.chinese ? zh : en);

function close() {
  dialog.value?.close();
  emit("close");
}

function confirm() {
  setTimezone(draft.value);
  close();
}

onMounted(() => dialog.value?.showModal());
</script>

<template>
  <dialog
    ref="dialog"
    class="sim-timezone-dialog"
    aria-labelledby="sim-timezone-title"
    @cancel.prevent="close"
    @click.self="close"
  >
    <form @submit.prevent="confirm">
      <header>
        <h2 id="sim-timezone-title">{{ t("设置时区", "Set time zone") }}</h2>
        <button
          class="sim-icon"
          type="button"
          :title="t('关闭', 'Close')"
          @click="close"
        >
          <X />
        </button>
      </header>
      <fieldset :aria-label="t('时区', 'Time zone')">
        <label v-for="zone in timezones" :key="zone.value">
          <input
            v-model="draft"
            type="radio"
            name="sim-timezone"
            :value="zone.value"
          />
          {{ zone.label }}
        </label>
      </fieldset>
      <footer>
        <button class="sim-button" type="button" @click="close">
          {{ t("取消", "Cancel") }}
        </button>
        <button class="sim-button primary" type="submit">
          {{ t("确认", "Confirm") }}
        </button>
      </footer>
    </form>
  </dialog>
</template>
