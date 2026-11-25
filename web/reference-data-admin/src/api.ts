export interface Session {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  role: string;
}

export interface Dataset {
  datasetKey: string;
  name: string;
  currentReleaseId: number | null;
}

export interface Release {
  id: number;
  version: string;
  sourceUrl: string;
  sourceRevision: string;
  sha256: string;
  createdAt: string;
  publishedAt: string | null;
  rowCount: number;
}

export interface Node {
  code: string;
  parentCode: string | null;
  name: string;
  level: number;
  sourceMetadata: { type?: string; typeSource?: string; shortCode?: string; childrenCount?: number } | null;
}

export interface NodePage {
  datasetKey: string;
  releaseId: number;
  rows: Node[];
  nextAfter: string | null;
}

export interface Change {
  code: string;
  kind: 'ADDED' | 'CHANGED' | 'REMOVED';
  previousName: string | null;
  nextName: string | null;
  previousParentCode: string | null;
  nextParentCode: string | null;
  previousLevel: number | null;
  nextLevel: number | null;
}

export interface Comparison {
  currentReleaseId: number | null;
  counts: { added: number; changed: number; removed: number };
  sample: Change[];
}

export interface Publication {
  id: number;
  releaseId: number;
  previousReleaseId: number | null;
  publishedBy: number;
  publishedAt: string;
}

export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
  ) {
    super(message);
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers);
  if (init?.method && init.method !== 'GET') {
    const csrf = await api<{ headerName: string; token: string }>('/api/auth/csrf');
    headers.set(csrf.headerName, csrf.token);
  }
  if (typeof init?.body === 'string') headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin' });
  if (!response.ok) {
    const error = await response.json().catch(() => ({}));
    throw new ApiError(response.status, error.detail || error.message || '请求失败（' + response.status + '）');
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}
