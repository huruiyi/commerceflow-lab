<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowUpRight, Store } from 'lucide-vue-next'
import BrowserLaunchButtons from './BrowserLaunchButtons.vue'
import { serviceLogins, statusOf, type Service } from './navigation'

const props = defineProps<{ service: Service; stale: boolean }>()
defineEmits<{ details: [id: string]; login: [id: string] }>()

const status = computed(() => statusOf(props.service, props.stale))
const browserFeedback = ref<{ message: string; error: boolean }>()
</script>

<template>
  <article class="service-card featured-portal" aria-labelledby="commerce-portal-heading">
    <header class="service-card-header">
      <span class="service-icon"><Store :size="22" /></span>
      <div class="service-card-name">
        <h2 id="commerce-portal-heading">{{ service.name }}</h2>
        <small>{{ service.id }}</small>
      </div>
      <span class="badge" :class="status.tone"><i></i>{{ status.label }}</span>
    </header>
    <div v-if="service.links.length" class="portal-entry-grid">
      <div v-for="link in service.links" :key="link.url" class="portal-entry">
        <a :href="link.url" :title="link.role || undefined" target="_blank" rel="noopener noreferrer">
          {{ link.label }} <ArrowUpRight :size="14" />
        </a>
        <BrowserLaunchButtons v-if="link.browserLaunch" :service-id="service.id" :link="link"
          @feedback="(message, error) => browserFeedback = { message, error }" />
      </div>
    </div>
    <footer class="service-card-footer">
      <div class="service-card-actions">
        <button v-if="serviceLogins[service.id]" type="button" class="text-button login-button"
          aria-haspopup="dialog" @click="$emit('login', service.id)">登录说明</button>
        <button type="button" class="text-button" @click="$emit('details', service.id)">查看详情</button>
      </div>
    </footer>
    <p v-if="browserFeedback?.message" class="browser-launch-feedback"
      :class="{ 'launch-error': browserFeedback.error }" role="status">{{ browserFeedback.message }}</p>
  </article>
</template>
