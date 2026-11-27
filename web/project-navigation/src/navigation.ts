import {
  Activity, Boxes, CircleAlert, CircleCheck, CircleHelp, CircleMinus, Clock3, Database,
  FlaskConical, LayoutDashboard, Server, Store,
} from 'lucide-vue-next'

export interface Address {
  protocol: string
  scope: 'HOST' | 'INTERNAL'
  value: string
}

export interface ServiceLink {
  label: string
  url: string
  role: string
  browserLaunch: boolean
}

export interface Instance {
  name: string
  state: string
  health: string
  exitCode: number
  restarts: number
  oomKilled: boolean
  startedAt: string
  finishedAt: string
}

export interface Probe {
  address: string
  status: string
  detail: string
  durationMs: number | null
}

export interface Service {
  id: string
  name: string
  group: string
  kind: 'SERVICE' | 'TASK'
  status: string
  profiles: string[]
  schema: string
  addresses: Address[]
  links: ServiceLink[]
  instances: Instance[]
  probes: Probe[]
}

interface ServiceLogin {
  method: '账号密码' | '管理令牌'
  defaults: { label: string; value: string }[]
  overrideVariables?: string[]
  note?: string
  tokenSource?: { service: string; variable: string }
}

function passwordLogin(username: string, password: string, overrideVariables?: string[], note?: string): ServiceLogin {
  return {
    method: '账号密码',
    defaults: [{ label: '用户名', value: username }, { label: '密码', value: password }],
    overrideVariables,
    note,
  }
}

// These values are the repository defaults for local development.
export const serviceLogins: Partial<Record<string, ServiceLogin>> = {
  'logistics-simulator-web': {
    method: '管理令牌',
    defaults: [{ label: '管理令牌', value: 'demo-logistics-admin-token' }],
    tokenSource: { service: 'logistics-simulator-service', variable: 'LOGISTICS_ADMIN_TOKEN' },
  },
  'commerce-portal-angular': {
    method: '账号密码',
    defaults: [
      { label: '顾客邮箱', value: 'demo@example.com' },
      { label: '商家邮箱', value: 'merchant@example.com' },
      { label: '管理员邮箱', value: 'admin@example.com' },
      { label: '通用密码', value: 'demo-password' },
    ],
  },
  'warehouse-simulator-web': {
    method: '管理令牌',
    defaults: [{ label: '管理令牌', value: 'demo-warehouse-admin-token' }],
    tokenSource: { service: 'warehouse-simulator-service', variable: 'WAREHOUSE_ADMIN_TOKEN' },
  },
  'messaging-simulator-web': {
    method: '管理令牌',
    defaults: [{ label: '管理令牌', value: 'demo-messaging-admin-token' }],
    tokenSource: { service: 'messaging-simulator-service', variable: 'MESSAGING_ADMIN_TOKEN' },
  },
  'payment-simulator-web': {
    method: '账号密码',
    defaults: [
      { label: '管理员用户名', value: 'payment-admin' },
      { label: '运营员用户名', value: 'payment-operator' },
      { label: '审计员用户名', value: 'payment-auditor' },
      { label: '密码', value: 'demo-password' },
    ],
    note: '三个教学账号使用相同的本地默认密码。',
  },
  grafana: passwordLogin('admin', 'commerceflow-grafana', undefined, '首次初始化使用此账号。已修改密码时，使用修改后的密码。'),
  'rabbitmq-broker': passwordLogin('commerceflow', 'commerceflow-password', [
    'RABBITMQ_USERNAME', 'RABBITMQ_PASSWORD',
  ], '此账号用于管理台和 AMQP 连接。'),
  'notification-db-postgresql': passwordLogin('notification', 'notification-password', [
    'NOTIFICATION_DATABASE_USERNAME', 'NOTIFICATION_DATABASE_PASSWORD',
  ], '此账号用于容器网络内访问 notification 数据库。'),
}

export interface Snapshot {
  project: string
  dockerConnected: boolean
  collectedAt: string | null
  attemptedAt: string | null
  message: string
  services: Service[]
}

export const groups = [
  { id: 'overview', name: '运行总览', icon: LayoutDashboard },
  { id: 'business', name: '业务与后台', icon: Store },
  { id: 'simulators', name: '外部模拟器', icon: FlaskConical },
  { id: 'services', name: '后端服务', icon: Server },
  { id: 'databases', name: '数据库', icon: Database },
  { id: 'middleware', name: '中间件', icon: Boxes },
  { id: 'monitoring', name: '监控与告警', icon: Activity },
]

export const summaries = [
  { id: 'ready', label: '已就绪', icon: CircleCheck, tone: 'green' },
  { id: 'progress', label: '启动中', icon: Clock3, tone: 'blue' },
  { id: 'issue', label: '异常', icon: CircleAlert, tone: 'red' },
  { id: 'inactive', label: '未运行', icon: CircleMinus, tone: 'slate' },
  { id: 'unknown', label: '待确认', icon: CircleHelp, tone: 'amber' },
]

const statuses: Record<string, { label: string; bucket: string }> = {
  READY: { label: '已就绪', bucket: 'ready' },
  STARTING: { label: '启动中', bucket: 'progress' },
  RESTARTING: { label: '重启中', bucket: 'issue' },
  FAILED: { label: '异常退出', bucket: 'issue' },
  DEGRADED: { label: '部分副本异常', bucket: 'issue' },
  MISSING: { label: '现役容器缺失', bucket: 'issue' },
  UNHEALTHY: { label: '不健康', bucket: 'issue' },
  UNREACHABLE: { label: '入口异常', bucket: 'issue' },
  STOPPED: { label: '已停止', bucket: 'inactive' },
  PAUSED: { label: '已暂停', bucket: 'inactive' },
  NOT_CREATED: { label: '未创建', bucket: 'inactive' },
  PROFILE_INACTIVE: { label: '可选 · 未启动', bucket: 'inactive' },
  RUNNING: { label: '运行中 · 待确认', bucket: 'unknown' },
  UNKNOWN: { label: '状态未知', bucket: 'unknown' },
  TASK_PENDING: { label: '待执行', bucket: 'inactive' },
  TASK_RUNNING: { label: '执行中', bucket: 'progress' },
  TASK_DONE: { label: '任务成功', bucket: 'ready' },
  TASK_FAILED: { label: '任务失败', bucket: 'issue' },
}

export function statusOf(service: Service, stale = false) {
  const status = (stale ? statuses.UNKNOWN : statuses[service.status]) ?? statuses.UNKNOWN
  return { ...status, tone: summaries.find((item) => item.id === status.bucket)?.tone ?? 'slate' }
}

export const lifecycle: Record<string, string> = {
  created: '已创建', running: '运行中', restarting: '重启中', exited: '已退出',
  paused: '已暂停', dead: '已失效', removing: '移除中',
}

export const health: Record<string, string> = {
  healthy: '健康', starting: '检查中', unhealthy: '不健康', none: '未配置', pending: '暂无结果',
}

export const reachability: Record<string, string> = {
  REACHABLE: '可达', REDIRECT: '可达 · 重定向', AUTH_REQUIRED: '需要认证',
  FAILED: '响应异常', TIMEOUT: '超时', UNREACHABLE: '连接失败',
}

export function timeLabel(value: string | null, long = false) {
  if (!value) return '—'
  const date = new Date(value)
  return long ? date.toLocaleString('zh-CN', { hour12: false })
    : date.toLocaleTimeString('zh-CN', { hour12: false })
}
