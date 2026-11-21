<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import {
  Activity, ArrowDownRight, ArrowUpRight, ChevronRight, CircleAlert, Copy, ExternalLink,
  LayoutGrid, Layers3, List, Menu, RefreshCw, Search, X,
} from 'lucide-vue-next'
import BrowserLaunchButtons from './BrowserLaunchButtons.vue'
import CommercePortalCard from './CommercePortalCard.vue'
import NavigationCards from './NavigationCards.vue'
import {
  groups, health, lifecycle, reachability, serviceLogins, statusOf, summaries, timeLabel,
  type Service, type Snapshot,
} from './navigation'

const route = useRoute()
const router = useRouter()
const commercePortalId = 'commerce-portal-angular'
const group = computed(() => groups.find((item) => item.id === route.params.group) ?? groups[0])
const overview = computed(() => group.value.id === 'overview')
const layouts = [
  { id: 'cards', name: '卡片', icon: LayoutGrid },
  { id: 'list', name: '列表', icon: List },
] as const
type Layout = typeof layouts[number]['id']
const layoutStorageKey = 'commerceflow.navigation.service-layout'

function parseLayout(value: unknown): Layout | undefined {
  return value === 'cards' || value === 'list' ? value : undefined
}

function readLayoutPreference(): Layout | undefined {
  try {
    return parseLayout(localStorage.getItem(layoutStorageKey))
  } catch {
    return undefined
  }
}

const preferredLayout = ref(readLayoutPreference())
const requestedLayout = computed(() => parseLayout(route.query.view))
const layout = computed<Layout>(() => requestedLayout.value
  ?? preferredLayout.value ?? (overview.value ? 'list' : 'cards'))
const browserFeedback = ref<{ message: string; error: boolean }>()
const snapshot = ref<Snapshot>()
const loadError = ref(false)
const loading = ref(false)
const now = ref(Date.now())
const query = ref('')
const filter = ref('all')
const showTasks = ref(false)
const mobileNav = ref(false)
const searchInput = ref<HTMLInputElement>()
const detailsDialog = ref<HTMLDialogElement>()
const selectedId = ref('')
const loginOnly = ref(false)
const feedback = ref('')
let poll: ReturnType<typeof setInterval> | undefined
let feedbackTimer: ReturnType<typeof setTimeout> | undefined
let request: AbortController | undefined

const services = computed(() => snapshot.value?.services ?? [])
const firstLoad = computed(() => !loadError.value && !snapshot.value?.attemptedAt)
const stale = computed(() => {
  const data = snapshot.value
  return loadError.value || Boolean(data?.attemptedAt && (!data.dockerConnected
    || !data.collectedAt || now.value - Date.parse(data.collectedAt) > 20_000))
})
const stats = computed(() => summaries.map((item) => ({
  ...item,
  count: services.value.filter((service) => (service.kind === 'SERVICE' || item.id === 'issue')
    && statusOf(service, stale.value).bucket === item.id).length,
})))
const scoped = computed(() => {
  if (overview.value || query.value.trim()) return services.value
  return services.value.filter((service) => service.group === group.value.id)
})
const filtered = computed(() => {
  const tokens = query.value.toLowerCase().trim().split(/\s+/).filter(Boolean)
  const order = ['issue', 'progress', 'ready', 'unknown', 'inactive']
  return scoped.value.filter((service) => {
    if (service.kind === 'TASK' && !showTasks.value && !tokens.length
      && statusOf(service, stale.value).bucket !== 'issue') return false
    if (filter.value !== 'all' && statusOf(service, stale.value).bucket !== filter.value) return false
    const text = [
      service.name, service.id, service.schema, ...service.profiles,
      ...service.addresses.map((address) => address.value + ' ' + address.protocol),
      ...service.links.map((link) => link.label + ' ' + link.role + ' ' + link.url),
    ].join(' ').toLowerCase()
    return tokens.every((token) => text.includes(token))
  }).sort((a, b) => order.indexOf(statusOf(a, stale.value).bucket) - order.indexOf(statusOf(b, stale.value).bucket)
    || a.name.localeCompare(b.name, 'zh-CN'))
})
const pinnedPortal = computed(() => group.value.id === 'business'
  ? services.value.find((service) => service.id === commercePortalId)
  : undefined)
const serviceEntries = computed(() => filtered.value.filter((service) => service.id !== pinnedPortal.value?.id))
const listEntries = computed(() => serviceEntries.value.map((service) => ({
  service,
  browserLink: service.links.find((link) => link.browserLaunch),
})))
const quickLinks = computed(() => [
  { id: 'commerce-portal-angular', label: '商城门户', subtitle: '商城与业务后台', icon: groups[1].icon, tone: 'blue' },
  { id: 'payment-simulator-web', label: '支付管理台', subtitle: '支付平台模拟器', icon: groups[2].icon, tone: 'amber' },
  { id: 'kafka-console', label: 'Kafka UI', subtitle: '主题与消息', icon: groups[5].icon, tone: 'violet' },
  { id: 'grafana', label: 'Grafana', subtitle: '指标与仪表盘', icon: Activity, tone: 'green' },
].map((item) => ({ ...item, url: services.value.find((service) => service.id === item.id)?.links[0]?.url })))
const selected = computed(() => services.value.find((service) => service.id === selectedId.value))
const selectedLogin = computed(() => serviceLogins[selectedId.value])
const tokenCommand = computed(() => {
  const source = selectedLogin.value?.tokenSource
  return source ? ['docker compose exec -T', source.service, 'printenv', source.variable].join(' ') : ''
})

watch(() => route.path, () => {
  filter.value = 'all'
  mobileNav.value = false
})

watch([() => route.path, layout], () => { browserFeedback.value = undefined })

watch(requestedLayout, (requested) => {
  if (!requested) return
  preferredLayout.value = requested
  try {
    localStorage.setItem(layoutStorageKey, requested)
  } catch {
    // The in-memory preference still applies when browser storage is unavailable.
  }
}, { immediate: true })

function switchLayout(nextLayout: Layout) {
  if (nextLayout === layout.value) return
  void router.push({ query: { ...route.query, view: nextLayout } })
}

async function refresh() {
  if (loading.value) return
  loading.value = true
  request = new AbortController()
  try {
    const response = await fetch('/api/navigation/snapshot', {
      cache: 'no-store', signal: AbortSignal.any([request.signal, AbortSignal.timeout(4000)]),
    })
    if (!response.ok) throw new Error('状态请求失败')
    snapshot.value = await response.json() as Snapshot
    loadError.value = false
  } catch {
    loadError.value = true
  } finally {
    now.value = Date.now()
    loading.value = false
  }
}

function issueCount(id: string) {
  return services.value.filter((service) => service.group === id
    && statusOf(service, stale.value).bucket === 'issue').length
}

function containerText(service: Service) {
  if (!service.instances.length) return '—'
  if (service.instances.length === 1) {
    const state = service.instances[0].state
    return lifecycle[state] ?? state
  }
  const running = service.instances.filter((item) => item.state === 'running').length
  return `${running}/${service.instances.length} 运行`
}

function healthText(service: Service) {
  if (!service.instances.length || service.kind === 'TASK') return '—'
  if (service.instances.length === 1) {
    const [instance] = service.instances
    if (instance.state !== 'running') return '—'
    return health[instance.health] ?? instance.health
  }
  if (service.instances.every((instance) => instance.health === 'none')) return '未配置'
  const ok = service.instances.filter((item) => item.state === 'running' && item.health === 'healthy').length
  return `${ok}/${service.instances.length} 健康`
}

function probeText(service: Service) {
  if (!service.probes.length) {
    if (service.addresses.some((address) => address.scope === 'HOST')) return '未探测'
    return service.addresses.length ? '仅容器内' : '—'
  }
  if (service.probes.length === 1) return reachability[service.probes[0].status] ?? '未知'
  const ok = service.probes.filter((probe) => ['REACHABLE', 'AUTH_REQUIRED', 'REDIRECT'].includes(probe.status)).length
  return `${ok}/${service.probes.length} 可达`
}

function serviceIcon(service: Service) {
  return groups.find((item) => item.id === service.group)?.icon ?? Layers3
}

async function openDetails(id: string, mode: 'details' | 'login' = 'details') {
  loginOnly.value = mode === 'login'
  selectedId.value = id
  await nextTick()
  detailsDialog.value?.showModal()
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    feedback.value = '已复制到剪贴板'
  } catch {
    feedback.value = '复制失败，可选中文本手动复制'
  }
  clearTimeout(feedbackTimer)
  feedbackTimer = setTimeout(() => { feedback.value = '' }, 2400)
}

function preferredAddress(service: Service) {
  return service.addresses.find((address) => address.scope === 'HOST') ?? service.addresses[0]
}

function focusSearch(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchInput.value?.focus()
  }
}

function selectStat(id: string) {
  filter.value = filter.value === id ? 'all' : id
}

onMounted(() => {
  void refresh()
  poll = setInterval(refresh, 5000)
  window.addEventListener('keydown', focusSearch)
})

onBeforeUnmount(() => {
  clearInterval(poll)
  clearTimeout(feedbackTimer)
  request?.abort()
  window.removeEventListener('keydown', focusSearch)
})
</script>

<template>
  <div class="shell">
    <button v-if="mobileNav" class="mobile-overlay" aria-label="关闭导航" @click="mobileNav = false"></button>
    <aside class="sidebar" :class="{ expanded: mobileNav }">
      <RouterLink class="brand" to="/">
        <span class="brand-mark"><Layers3 :size="23" :stroke-width="2.1" /></span>
        <span><strong>CommerceFlow</strong><small>运行控制台</small></span>
      </RouterLink>
      <nav aria-label="服务分类">
        <RouterLink v-for="item in groups" :key="item.id" class="nav-link"
          :class="{ current: group.id === item.id }"
          :to="item.id === 'overview' ? { name: 'overview' } : { name: 'group', params: { group: item.id } }">
          <component :is="item.icon" :size="18" />
          <span>{{ item.name }}</span>
          <span v-if="issueCount(item.id)" class="nav-count">{{ issueCount(item.id) }}</span>
        </RouterLink>
      </nav>
      <div class="sidebar-footer">
        <div class="stack-icon"><Boxes :size="17" /></div>
        <div><strong>本地 Compose</strong><small>{{ snapshot?.project ?? 'fairy-commerceflow' }}</small></div>
      </div>
    </aside>

    <div class="workspace">
      <header class="topbar">
        <div class="breadcrumbs">
          <button type="button" class="icon-button menu-button" aria-label="打开导航" @click="mobileNav = !mobileNav"><Menu :size="21" /></button>
          <span>工作台</span><ChevronRight :size="14" /><h1 class="breadcrumb-title">{{ group.name }}</h1>
        </div>
        <div class="header-actions">
          <div class="search-box">
            <Search :size="17" />
            <input ref="searchInput" v-model="query" placeholder="搜索服务、端口、Schema" aria-label="全局搜索服务">
            <button v-if="query" type="button" class="plain-button" aria-label="清空搜索" @click="query = ''"><X :size="15" /></button>
            <kbd v-else>Ctrl K</kbd>
          </div>
          <span class="docker-indicator" :class="firstLoad ? 'amber' : stale ? 'red' : 'green'">
            <i></i>{{ firstLoad ? '等待采集' : stale ? '采集异常' : 'Docker 已连接' }}
          </span>
          <button type="button" class="icon-button" :disabled="loading" aria-label="刷新状态" title="刷新状态" @click="refresh">
            <RefreshCw :size="17" :class="{ spinning: loading }" />
          </button>
        </div>
      </header>

      <main>
        <div v-if="stale" class="notice" role="status">
          <CircleAlert :size="19" />
          <span>{{ loadError ? '暂时无法连接控制台 API。' : snapshot?.message || '采集结果暂未更新。' }}{{ snapshot?.collectedAt ? ' 当前显示上次快照，状态已过期。' : '' }}</span>
        </div>

        <div v-if="overview" class="stats">
          <button v-for="stat in stats" :key="stat.id" class="stat-card" :class="[stat.tone, { active: filter === stat.id }]"
            type="button" :aria-pressed="filter === stat.id" @click="selectStat(stat.id)">
            <span class="stat-title">{{ stat.label }}</span>
            <span class="stat-icon"><component :is="stat.icon" :size="19" /></span>
            <strong>{{ firstLoad ? '—' : stat.count }}</strong>
            <ArrowDownRight :size="15" class="stat-arrow" />
          </button>
        </div>

        <section v-if="overview && !query.trim() && filter === 'all' && quickLinks.some((item) => item.url)"
          class="quick-section">
          <div class="section-heading">
            <h2>常用入口</h2>
          </div>
          <div class="quick-grid">
            <a v-for="entry in quickLinks.filter((item) => item.url)" :key="entry.id" class="quick-card"
              :href="entry.url" target="_blank" rel="noopener noreferrer">
              <span class="quick-icon" :class="entry.tone"><component :is="entry.icon" :size="21" /></span>
              <span class="quick-text"><strong>{{ entry.label }}</strong><small>{{ entry.subtitle }}</small></span>
              <ArrowUpRight class="quick-arrow" :size="17" />
            </a>
          </div>
        </section>

        <CommercePortalCard v-if="pinnedPortal" :service="pinnedPortal" :stale="stale"
          @details="openDetails" @login="openDetails($event, 'login')" />

        <section v-if="group.id !== 'business' || !pinnedPortal || serviceEntries.length || query.trim()"
          :class="{ 'table-card': layout === 'list' }" aria-labelledby="service-list-heading">
          <div class="table-toolbar" :class="{ 'standalone-toolbar': layout === 'cards' }">
            <div class="service-toolbar-heading">
              <h2 id="service-list-heading">{{ query ? '搜索结果' : '服务' }}</h2>
              <span class="list-count">{{ serviceEntries.length }}</span>
              <div class="last-updated">
                <span></span>{{ stale ? '最后成功采集' : '最近采集' }} {{ timeLabel(snapshot?.collectedAt ?? null) }}
              </div>
            </div>
            <div class="table-filters">
              <label class="tasks-toggle"><input v-model="showTasks" type="checkbox"> 显示一次性任务</label>
              <select v-model="filter" aria-label="筛选服务状态">
                <option value="all">全部状态</option>
                <option v-for="item in summaries" :key="item.id" :value="item.id">{{ item.label }}</option>
              </select>
              <div class="layout-switch" role="group" aria-label="服务显示方式">
                <button v-for="option in layouts" :key="option.id" type="button"
                  :aria-pressed="layout === option.id" @click="switchLayout(option.id)">
                  <component :is="option.icon" :size="14" />{{ option.name }}
                </button>
              </div>
            </div>
          </div>
          <p v-if="layout === 'list' && browserFeedback?.message"
            class="browser-launch-feedback list-browser-feedback" :class="{ 'launch-error': browserFeedback.error }"
            role="status">{{ browserFeedback.message }}</p>
          <div v-if="!serviceEntries.length" class="empty">
            <Search :size="25" />
            <strong>{{ firstLoad ? '正在采集服务状态' : pinnedPortal ? '没有其他匹配的服务' : '没有匹配的服务' }}</strong>
            <button v-if="query || filter !== 'all'" class="text-button" @click="query = ''; filter = 'all'">清除筛选</button>
          </div>
          <NavigationCards v-else-if="layout === 'cards'" :services="serviceEntries" :stale="stale"
            :show-group-headings="overview || Boolean(query.trim())"
            @details="openDetails" @login="openDetails($event, 'login')" @copy="copy" />
          <div v-else class="table-scroll">
            <table>
              <thead>
                <tr>
                  <th scope="col">服务</th>
                  <th scope="col">综合状态</th>
                  <th scope="col" class="desktop-column">容器</th>
                  <th scope="col" class="desktop-column">健康检查</th>
                  <th scope="col" class="desktop-column">入口探测</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="{ service, browserLink } in listEntries" :key="service.id">
                  <td>
                    <button class="service-name" type="button" @click="openDetails(service.id)">
                      <span class="service-icon"><component :is="serviceIcon(service)" :size="17" /></span>
                      <span><strong>{{ service.name }}</strong><small>{{ service.id }}</small></span>
                    </button>
                  </td>
                  <td><span class="badge" :class="statusOf(service, stale).tone"><i></i>{{ statusOf(service, stale).label }}</span></td>
                  <td class="desktop-column">{{ stale ? '—' : containerText(service) }}</td>
                  <td class="desktop-column">{{ stale ? '—' : healthText(service) }}</td>
                  <td class="desktop-column">{{ stale ? '—' : probeText(service) }}</td>
                  <td>
                    <div class="row-actions">
                      <a v-if="service.links.length" :href="service.links[0].url" target="_blank" rel="noopener noreferrer"
                        class="icon-button small" :title="service.links[0].label" :aria-label="service.links[0].label"><ExternalLink :size="15" /></a>
                      <button v-if="preferredAddress(service)" type="button" class="icon-button small"
                        aria-label="复制地址" title="复制地址" @click="copy(preferredAddress(service)!.value)"><Copy :size="15" /></button>
                      <BrowserLaunchButtons v-if="browserLink" :service-id="service.id" :link="browserLink"
                        :label="service.name" @feedback="(message, error) => browserFeedback = { message, error }" />
                      <button v-if="serviceLogins[service.id]" type="button" class="text-button login-button"
                        aria-haspopup="dialog" @click="openDetails(service.id, 'login')">登录说明</button>
                      <button type="button" class="text-button details-button" @click="openDetails(service.id)">详情 <ChevronRight :size="14" /></button>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </main>
    </div>

    <dialog ref="detailsDialog" class="detail-drawer" aria-labelledby="detail-heading"
      @click.self="detailsDialog?.close()" @close="selectedId = ''">
      <div v-if="selected" class="drawer-body">
        <header class="drawer-header">
          <div class="drawer-title">
            <span class="quick-icon blue"><component :is="serviceIcon(selected)" :size="23" /></span>
            <div><h2 id="detail-heading">{{ selected.name }}</h2><small>{{ selected.id }}</small></div>
          </div>
          <button type="button" class="icon-button" :aria-label="loginOnly ? '关闭登录说明' : '关闭详情'"
            autofocus @click="detailsDialog?.close()"><X :size="21" /></button>
        </header>
        <div class="drawer-content">
          <div v-if="!loginOnly" class="drawer-state">
            <span class="badge" :class="statusOf(selected, stale).tone"><i></i>{{ statusOf(selected, stale).label }}</span>
            <span v-if="stale">状态过期</span>
            <span v-for="profile in selected.profiles" :key="profile" class="profile-tag">{{ profile }}</span>
          </div>
          <section v-if="!loginOnly && selected.links.length">
            <h3>访问入口</h3>
            <div class="detail-list">
              <a v-for="link in selected.links" :key="link.url" :href="link.url" target="_blank" rel="noopener noreferrer" class="detail-row">
                <span>{{ link.label }} <small v-if="link.role"> · {{ link.role }}</small></span><ArrowUpRight :size="16" />
              </a>
            </div>
          </section>
          <section v-if="selectedLogin">
            <h3>登录说明 <span class="list-count">{{ selectedLogin.method }}</span></h3>
            <div class="detail-list">
              <div v-for="entry in selectedLogin.defaults" :key="entry.label" class="detail-row">
                <div>
                  <small>本地默认{{ entry.label }}</small>
                  <code>{{ entry.value }}</code>
                </div>
                <button type="button" class="icon-button small" :aria-label="'复制默认' + entry.label"
                  :title="'复制默认' + entry.label" @click="copy(entry.value)">
                  <Copy :size="15" />
                </button>
              </div>
              <div v-if="selectedLogin.note || selectedLogin.overrideVariables?.length" class="detail-row">
                <div class="login-guidance">
                  <small>配置说明</small>
                  <p v-if="selectedLogin.note">{{ selectedLogin.note }}</p>
                  <p v-if="selectedLogin.overrideVariables?.length">
                    自定义配置可在仓库根目录 <code class="inline-code">.env</code> 查找以下变量
                    <code v-for="variable in selectedLogin.overrideVariables" :key="variable">{{ variable }}</code>
                  </p>
                </div>
              </div>
              <template v-if="selectedLogin.tokenSource">
                <div class="detail-row">
                  <div class="login-guidance">
                    <small>自定义令牌</small>
                    <p>直接填写令牌，无需加 Bearer。若已自定义，可查看仓库根目录
                      <code class="inline-code">.env</code> 中的
                      <code class="inline-code">{{ selectedLogin.tokenSource.variable }}</code>，
                      或在仓库根目录用 PowerShell 执行下面的命令，查看容器当前生效的值。</p>
                  </div>
                </div>
                <div class="detail-row">
                  <div><code>{{ tokenCommand }}</code></div>
                  <button type="button" class="icon-button small" aria-label="复制查询命令"
                    title="复制查询命令" @click="copy(tokenCommand)">
                    <Copy :size="15" />
                  </button>
                </div>
              </template>
            </div>
          </section>
          <section v-if="!loginOnly && (selected.addresses.length || selected.schema)">
            <h3>连接信息</h3>
            <div class="detail-list">
              <div v-for="address in selected.addresses" :key="address.scope + address.value" class="detail-row">
                <div><small>{{ address.scope === 'HOST' ? '宿主机' : '容器网络' }} · {{ address.protocol }}</small><code>{{ address.value }}</code></div>
                <button type="button" class="icon-button small" title="复制地址" aria-label="复制地址" @click="copy(address.value)"><Copy :size="15" /></button>
              </div>
              <div v-if="selected.schema" class="detail-row"><div><small>Schema</small><code>{{ selected.schema }}</code></div>
                <button type="button" class="icon-button small" title="复制 Schema" aria-label="复制 Schema" @click="copy(selected.schema)"><Copy :size="15" /></button>
              </div>
            </div>
          </section>
          <section v-if="!loginOnly">
            <h3>容器实例 <span class="list-count">{{ selected.instances.length }}</span></h3>
            <p v-if="!selected.instances.length" class="detail-note">{{ stale ? '暂无当前实例数据。' : '当前没有容器实例。' }}</p>
            <div v-else class="detail-list">
              <article v-for="instance in selected.instances" :key="instance.name" class="instance">
                <code>{{ instance.name }}</code>
                <dl>
                  <div><dt>容器状态</dt><dd>{{ lifecycle[instance.state] ?? instance.state }}</dd></div>
                  <div>
                    <dt>{{ instance.state === 'running' ? '健康检查' : '最后记录的健康检查' }}</dt>
                    <dd>{{ health[instance.health] ?? instance.health }}</dd>
                  </div>
                  <div><dt>重启次数</dt><dd>{{ instance.restarts }}</dd></div>
                  <div v-if="instance.state === 'exited' || instance.state === 'dead'"><dt>退出码</dt><dd>{{ instance.exitCode }}</dd></div>
                  <div v-if="instance.startedAt"><dt>启动时间</dt><dd>{{ timeLabel(instance.startedAt, true) }}</dd></div>
                  <div v-if="instance.finishedAt"><dt>退出时间</dt><dd>{{ timeLabel(instance.finishedAt, true) }}</dd></div>
                  <div v-if="instance.oomKilled"><dt>内存</dt><dd>内存不足被终止</dd></div>
                </dl>
              </article>
            </div>
          </section>
          <section v-if="!loginOnly">
            <h3>入口探测</h3>
            <p v-if="!selected.probes.length" class="detail-note">暂无宿主机探测结果。{{ selected.profiles.length ? '所需 Profile 已在上方标注。' : '' }}</p>
            <div v-else class="detail-list">
              <div v-for="result in selected.probes" :key="result.address" class="detail-row">
                <div><code>{{ result.address }}</code><small>{{ reachability[result.status] }} · {{ result.detail }}</small></div>
                <small class="latency">{{ result.durationMs }} ms</small>
              </div>
            </div>
          </section>
        </div>
        <div v-if="!loginOnly" class="drawer-footer">
          <Activity :size="15" /> 采集于 {{ timeLabel(snapshot?.collectedAt ?? null, true) }}
        </div>
        <div v-if="feedback" class="toast" role="status">{{ feedback }}</div>
      </div>
    </dialog>
    <div v-if="feedback && !selected" class="toast" role="status">{{ feedback }}</div>
  </div>
</template>
