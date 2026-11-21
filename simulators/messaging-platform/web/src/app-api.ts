import { AdminRequestError } from '@commerceflow/simulator-admin/token-session';

export type Channel = 'SMS' | 'EMAIL';
export type Status = 'ACCEPTED' | 'DELIVERED' | 'FAILED';
export type Fault = 'THROTTLE_ONCE' | 'TIMEOUT_AFTER_ACCEPT' | 'DELIVERY_FAILED';

export interface Delivery {
  requestId: string;
  providerId: string;
  channel: Channel;
  recipientMask: string;
  reference: string;
  status: Status;
  acceptedAt: string;
  finishedAt: string | null;
}

export interface DeliveryPage {
  entries: Delivery[];
  total: number;
  page: number;
}

export interface Command {
  requestId: string;
  channel: Channel;
  recipient: string;
  reference: string;
  message: string;
  subject?: string;
}

export async function admin<T>(token: string, path: string, method = 'GET', body?: object): Promise<T> {
  const response = await fetch('/api/admin/' + path, {
    method,
    cache: 'no-store',
    headers: {
      Authorization: 'Bearer ' + token,
      'Content-Type': 'application/json',
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const messages: Record<number, string> = {
      400: '请检查投递内容和模拟收件人格式。',
      401: '管理令牌无效。',
      403: '当前环境未启用此操作。',
      404: '未找到投递记录。',
      409: '此编号已有不同的投递内容或故障配置。',
      429: '模拟供应商限流，请稍后用原编号重试。',
      504: '模拟供应商响应超时，请查询原投递。',
    };
    throw new AdminRequestError(response.status, messages[response.status] ?? '服务暂时不可用，请稍后重试。');
  }
  return (response.status === 204 ? undefined : await response.json()) as T;
}
