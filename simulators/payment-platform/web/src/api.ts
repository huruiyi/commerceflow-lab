import { t } from './i18n';

let csrfToken = '';
let csrfHeader = 'X-PAYMENT-XSRF-TOKEN';

export async function initializeCsrf() {
  const response = await fetch('/api/auth/csrf', { credentials: 'include' });
  if (!response.ok) throw new Error(t('csrfInitFailed'));
  const value = await response.json();
  csrfToken = value.token;
  csrfHeader = value.headerName;
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const method = (options.method || 'GET').toUpperCase();
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && !csrfToken && !path.startsWith('/api/checkout/')) {
    await initializeCsrf();
  }
  const headers = new Headers(options.headers);
  if (options.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method) && csrfToken) headers.set(csrfHeader, csrfToken);
  const response = await fetch(path, { ...options, headers, credentials: 'include' });
  if (response.status === 204) return undefined as T;
  const contentType = response.headers.get('content-type') || '';
  const body = contentType.includes('json') ? await response.json() : await response.text();
  if (!response.ok) throw new Error(body?.message || body || t('requestFailed', { status: response.status }));
  return body as T;
}

export function saveStepUpToken(token: string) {
  sessionStorage.setItem('commerceflow-pay-step-up', token);
}

export function takeStepUpToken() {
  const token = sessionStorage.getItem('commerceflow-pay-step-up') || '';
  sessionStorage.removeItem('commerceflow-pay-step-up');
  return token;
}

const bytesToHex = (bytes: Uint8Array) =>
  Array.from(bytes)
    .map((value) => value.toString(16).padStart(2, '0'))
    .join('');
const base64 = (bytes: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(bytes)));
const randomId = (bytes: number) => {
  const value = crypto.getRandomValues(new Uint8Array(bytes));
  return Array.from(value)
    .map((v) => v.toString(16).padStart(2, '0'))
    .join('');
};

async function importRsaPrivateKey(pem: string) {
  const clean = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, '');
  const binary = Uint8Array.from(atob(clean), (char) => char.charCodeAt(0));
  return crypto.subtle.importKey('pkcs8', binary, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
}

export async function signedApi<T>(
  path: string,
  method: string,
  body: string,
  credential: {
    appId: string;
    algorithm: 'HMAC_SHA256' | 'RSA_SHA256';
    secret?: string;
    privateKey?: string;
  },
): Promise<T> {
  const timestamp = Math.floor(Date.now() / 1000).toString();
  const nonce = randomId(18);
  const requestId = randomId(12);
  const bodyHash = bytesToHex(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(body))));
  const canonical = [method.toUpperCase(), path, timestamp, nonce, requestId, bodyHash].join('\n');
  let signature: ArrayBuffer;
  if (credential.algorithm === 'HMAC_SHA256') {
    const key = await crypto.subtle.importKey(
      'raw',
      new TextEncoder().encode(credential.secret || ''),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign'],
    );
    signature = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(canonical));
  } else {
    const key = await importRsaPrivateKey(credential.privateKey || '');
    signature = await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(canonical));
  }
  return api<T>(path, {
    method,
    body: body || undefined,
    headers: {
      'X-App-Id': credential.appId,
      'X-Signature-Algorithm': credential.algorithm,
      'X-Timestamp': timestamp,
      'X-Nonce': nonce,
      'X-Request-Id': requestId,
      'X-Body-SHA256': bodyHash,
      'X-Signature': base64(signature),
    },
  });
}
