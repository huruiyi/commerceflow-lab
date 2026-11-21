export const shipmentStates: Record<string, string> = {
  CREATED: '已建单',
  PICKED_UP: '已揽收',
  IN_TRANSIT: '运输中',
  AT_STATION: '已到站',
  OUT_FOR_DELIVERY: '派送中',
  DELIVERED: '已签收',
  EXCEPTION: '异常',
};

export const callbackStates: Record<string, string> = {
  NEW: '待投递',
  PROCESSING: '投递中',
  RETRY: '待重试',
  PUBLISHED: '已投递',
  DEAD: '已终止',
};

export type TestCallbackResult = 'ACCEPT' | 'REJECT';

export interface ShipmentSummary {
  merchantShipmentNo: string;
  orderNumber: string;
  carrier: string;
  trackingNumber: string;
  status: string;
  updatedAt: string;
}

export interface Shipment extends ShipmentSummary {
  id: number;
  platformShipmentNo: string;
  events: {
    eventId: string;
    status: string;
    location: string;
    description: string;
    occurredAt: string;
  }[];
}

export interface ShipmentPage {
  entries: ShipmentSummary[];
  total: number;
  page: number;
}

export interface Callback {
  id: number;
  shipmentId: number;
  eventId: string;
  status: string;
  attempts: number;
  maxAttempts: number;
  nextAttemptAt: string | null;
  updatedAt: string;
  lastError: string | null;
}

export interface AuditEvent {
  id: number;
  eventType: string;
  toStatus: string;
  operatedBy: string;
  reason: string | null;
  createdAt: string;
}

export async function admin<T>(
  token: string,
  path: string,
  method = 'GET',
  body?: Record<string, string | boolean>,
): Promise<T> {
  const headers = new Headers({ Authorization: 'Bearer ' + token });
  if (body) headers.set('Content-Type', 'application/json');
  const response = await fetch('/api/admin/logistics/' + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
    signal: AbortSignal.timeout(8000),
  });
  if (response.ok) return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  const messages: Record<number, string> = {
    400: '请求参数有误，请检查后重试。',
    401: '管理令牌无效，请检查当前配置。',
    404: '没有找到该记录，请刷新列表。',
    409: path === 'shipments' ? '物流单号已被使用，或本次请求已创建运单。' : '当前状态不允许此操作，请刷新详情。',
  };
  throw new Error(messages[response.status] ?? '物流模拟服务暂时没有返回成功结果，请刷新确认。');
}
