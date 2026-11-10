let csrfToken = ''
let csrfHeader = 'X-PAYMENT-XSRF-TOKEN'

export async function initializeCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' })
  if (!response.ok) throw new Error('无法初始化安全令牌')
  const value = await response.json()
  csrfToken = value.token
  csrfHeader = value.headerName
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase()
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && !csrfToken && !path.startsWith('/api/checkout/')) {
    await initializeCsrf()
  }
  const headers = new Headers(options.headers)
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfToken) headers.set(csrfHeader, csrfToken)
  const response = await fetch(path, { ...options, headers, credentials: 'include' })
  if (response.status === 204) return undefined as T
  const contentType = response.headers.get('content-type') || ''
  const body = contentType.includes('json') ? await response.json() : await response.text()
  if (!response.ok) throw new Error(body?.message || body || `请求失败 (${response.status})`)
  return body as T
}
