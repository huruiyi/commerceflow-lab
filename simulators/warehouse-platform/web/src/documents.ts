import { AdminRequestError } from '@commerceflow/simulator-admin/token-session';

export const outboundLabels = {
  PENDING: '待实发',
  DISPATCHED: '已确认实发',
  VOIDED: '已拒绝出库',
};
export const returnLabels = {
  WAITING_RECEIPT: '待实收',
  RECEIVED: '待质检',
  PASSED: '质检合格',
  FAILED: '质检不通过',
  SHORT_RECEIVED: '短收',
  REJECTED: '拒收',
};
export type Resource = 'outbounds' | 'returns';

export interface Outbound {
  outboundId: string;
  orderId: number;
  warehouseId: number;
  status: keyof typeof outboundLabels;
  planned: { orderLineId: number; productId: number; quantity: number }[];
  actual: { orderLineId: number; quantity: number }[];
  createdAt: string;
}

export interface ReturnDocument {
  returnId: string;
  outboundId: string;
  orderId: number;
  warehouseId: number;
  status: keyof typeof returnLabels;
  trackingNumber: string;
  expectedQuantity: number;
  receivedQuantity: number | null;
  supplementId: string | null;
  qualifiedQuantity: number | null;
  supplementNote: string | null;
}

export type WarehouseDocument = Outbound | ReturnDocument;

export interface WarehousePage {
  rows: WarehouseDocument[];
  total: number;
  page: number;
  size: number;
}

export function documentId(document: WarehouseDocument) {
  return 'returnId' in document ? document.returnId : document.outboundId;
}

export async function warehouseApi<Result>(token: string, path: string, body?: object): Promise<Result> {
  const result = await fetch('/api/admin/' + path, {
    method: body ? 'PUT' : 'GET',
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
    headers: {
      Authorization: 'Bearer ' + token,
      'Content-Type': 'application/json',
    },
  });
  if (!result.ok) {
    const error = (await result.json().catch(() => null)) as {
      detail?: string;
    } | null;
    const message = result.status === 401 ? '管理令牌无效，请检查当前配置。' : error?.detail;
    throw new AdminRequestError(result.status, message || '请求失败（' + result.status + '）');
  }
  return (await result.json()) as Result;
}
