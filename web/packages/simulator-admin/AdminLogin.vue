<script setup lang="ts">
import { ref, watch } from "vue";
import { Moon, Sun } from "@lucide/vue";
import VbenLoginIllustration from "./VbenLoginIllustration.vue";

const props = defineProps<{
  brand: string;
  title: string;
  description?: string;
  english?: boolean;
}>();
const dark = ref(localStorage.getItem("sim-admin-theme") === "dark");
const t = (zh: string, en: string) => (props.english ? en : zh);
watch(dark, (value) =>
  localStorage.setItem("sim-admin-theme", value ? "dark" : "light"),
);
</script>

<template>
  <main class="sim-auth" :class="{ 'sim-auth-dark': dark, dark: dark }">
    <header class="sim-auth-header">
      <div class="sim-brand">
        <span aria-hidden="true">C</span><strong>{{ brand }}</strong>
      </div>
      <div class="sim-auth-tools">
        <slot name="tools" />
        <button
          type="button"
          class="sim-icon"
          :title="
            dark ? t('切换浅色', 'Light mode') : t('切换深色', 'Dark mode')
          "
          :aria-pressed="dark"
          @click="dark = !dark"
        >
          <Sun v-if="dark" /><Moon v-else />
        </button>
      </div>
    </header>
    <aside class="sim-auth-art" aria-hidden="true">
      <VbenLoginIllustration class="sim-auth-illustration" />
    </aside>
    <section class="sim-auth-main" aria-labelledby="sim-auth-title">
      <div class="sim-auth-body">
        <h1 id="sim-auth-title">{{ title }}</h1>
        <p v-if="description" class="sim-auth-description">{{ description }}</p>
        <slot />
      </div>
    </section>
  </main>
</template>
