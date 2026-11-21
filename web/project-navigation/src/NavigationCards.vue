<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowUpRight, Copy } from 'lucide-vue-next'
import { groups, serviceLogins, statusOf, type Service } from './navigation'
import BrowserLaunchButtons from './BrowserLaunchButtons.vue'

const props = defineProps<{
  services: Service[]
  stale: boolean
  showGroupHeadings: boolean
}>()

defineEmits<{
  details: [id: string]
  login: [id: string]
  copy: [address: string]
}>()

const browserFeedback = ref<{ serviceId: string; message: string; error: boolean }>()

const consoleServicePairs: Partial<Record<string, string | null>> = {
  'logistics-simulator-web': 'logistics-simulator-service',
  'warehouse-simulator-web': 'warehouse-simulator-service',
  'messaging-simulator-web': 'messaging-simulator-service',
  'payment-simulator-web': 'payment-simulator-service',
  'kafka-console': 'kafka-broker',
  'rabbitmq-broker': null,
  grafana: 'tempo',
  alertmanager: 'alert-webhook',
  prometheus: null,
}
function consoleColumns<T extends Service>(entries: T[]): T[][] {
  const consoles = entries.filter((service) => Object.hasOwn(consoleServicePairs, service.id))
    .sort((a, b) => Number(Boolean(consoleServicePairs[b.id])) - Number(Boolean(consoleServicePairs[a.id]))
      || a.name.localeCompare(b.name, 'zh-CN'))
  const remaining = new Map(entries.map((service) => [service.id, service]))
  const columns = consoles.map((entry) => {
    remaining.delete(entry.id)
    const partnerId = consoleServicePairs[entry.id]
    const partner = partnerId ? remaining.get(partnerId) : undefined
    if (!partner) return [entry]
    remaining.delete(partner.id)
    return [entry, partner]
  })
  return [...columns, ...Array.from(remaining.values(), (service) => [service])]
}

const pairedGroups = ['simulators', 'middleware', 'monitoring']
const sections = computed(() => groups.slice(1).flatMap((group) => {
  const entries = props.services.filter((service) => service.group === group.id).map((service) => ({
    ...service,
    browserLink: service.links.find((link) => link.browserLaunch),
    address: service.addresses.find((address) => address.scope === 'HOST') ?? service.addresses[0],
  }))
  if (!entries.length) return []
  const columns = pairedGroups.includes(group.id) ? consoleColumns(entries) : entries.map((service) => [service])
  return [{ ...group, count: entries.length, columns, hasPairs: columns.some((column) => column.length > 1) }]
}))
</script>

<template>
  <section v-for="section in sections" :key="section.id" class="navigation-section"
    :class="{ 'middleware-section': section.id === 'middleware' }">
    <div v-if="showGroupHeadings" class="section-heading">
      <h2>{{ section.name }}</h2>
      <span class="list-count">{{ section.count }}</span>
    </div>
    <div class="navigation-grid" :class="{ 'paired-grid': section.hasPairs }">
      <div v-for="column in section.columns" :key="column[0].id" class="navigation-column">
        <article v-for="service in column" :key="service.id" class="service-card">
          <header class="service-card-header">
            <span class="service-icon"><component :is="section.icon" :size="18" /></span>
            <div class="service-card-name">
              <h3>{{ service.name }}</h3>
              <small>{{ service.id }}</small>
            </div>
            <span class="badge" :class="statusOf(service, stale).tone">
              <i></i>{{ statusOf(service, stale).label }}
            </span>
          </header>
          <div v-if="service.address" class="service-card-address">
            <span>{{ service.address.scope === 'HOST' ? '宿主机' : '容器网络' }}</span>
            <code>{{ service.address.value }}</code>
            <button type="button" class="icon-button small" aria-label="复制地址"
              title="复制地址" @click="$emit('copy', service.address.value)">
              <Copy :size="15" />
            </button>
          </div>
          <div v-if="service.links.length" class="service-card-links">
            <a v-for="link in service.links" :key="link.url" :href="link.url" :title="link.role || undefined"
              target="_blank" rel="noopener noreferrer">
              {{ link.label }} <ArrowUpRight :size="14" />
            </a>
          </div>
          <footer class="service-card-footer">
            <div class="service-card-footer-info">
              <BrowserLaunchButtons v-if="service.browserLink"
                :service-id="service.id" :link="service.browserLink" :label="service.name"
                @feedback="(message, error) => browserFeedback = { serviceId: service.id, message, error }" />
              <span v-if="service.schema || service.profiles.length">{{ service.schema || service.profiles.join(' · ') }}</span>
            </div>
            <div class="service-card-actions">
              <button v-if="serviceLogins[service.id]" type="button" class="text-button login-button"
                aria-haspopup="dialog" @click="$emit('login', service.id)">登录说明</button>
              <button type="button" class="text-button" @click="$emit('details', service.id)">查看详情</button>
            </div>
          </footer>
          <p v-if="browserFeedback?.serviceId === service.id && browserFeedback.message"
            class="browser-launch-feedback" :class="{ 'launch-error': browserFeedback.error }" role="status">
            {{ browserFeedback.message }}
          </p>
        </article>
      </div>
    </div>
  </section>

</template>
