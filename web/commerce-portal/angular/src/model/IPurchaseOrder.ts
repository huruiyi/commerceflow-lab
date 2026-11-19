export type PaymentChannel = 'ALIPAY' | 'WECHAT_PAY' | 'UNION_PAY';

export interface PurchaseOrderItem {
  id: number;
  productId: number;
  productName: string;
  thumbnailUrl: string;
  unitPrice: number;
  quantity: number;
  subtotal: number;
  discountAmount?: number;
}

export interface OrderLogisticsEvent {
  eventId: string;
  eventType: string;
  status: string;
  location: string;
  description: string;
  occurredAt: string;
}

export interface OrderLogistics {
  merchantShipmentNo: string;
  platformShipmentNo?: string;
  carrier: string;
  trackingNumber: string;
  status: string;
  shippedAt?: string;
  deliveredAt?: string;
  events: OrderLogisticsEvent[];
}

export interface OrderAddress {
  recipientName: string;
  phone: string;
  countryCode: string;
  stateProvince: string;
  city: string;
  district: string;
  addressLine1: string;
  addressLine2: string;
  postalCode: string;
  label: string;
}

export interface OrderPayment {
  paymentNumber: string;
  merchantNo: string;
  channel: string;
  channelTransactionNo: string;
  status: string;
  failureReason: string;
  amount: number;
  refundedAmount: number;
  currency: string;
  createdAt: string;
  paidAt?: string;
  expiresAt?: string;
  checkoutUrl?: string;
  actionType?: string;
  qrPayload?: string;
}

export interface OrderEvent {
  id: number;
  eventType: string;
  fromStatus?: string;
  toStatus?: string;
  detail?: string;
  createdAt: string;
}

export interface RefundEvent {
  id: number;
  eventType: string;
  fromStatus: string;
  toStatus: string;
  detail: string;
  createdAt: string;
}

export interface OrderRefund {
  refundNumber: string;
  orderId: number;
  status: string;
  amount: number;
  currency: string;
  reasonCode: string;
  reasonDetail: string;
  channelRefundNo?: string;
  failureReason?: string;
  requestedAt: string;
  succeededAt?: string;
  failedAt?: string;
  events: RefundEvent[];
}

export type OrderAction =
  | 'PAY'
  | 'REFRESH_PAYMENT'
  | 'CANCEL_ORDER'
  | 'REQUEST_REFUND'
  | 'VIEW_PAYMENT'
  | 'VIEW_REFUND'
  | 'VIEW_LOGISTICS'
  | 'COPY_TRACKING_NUMBER'
  | 'CONFIRM_RECEIPT'
  | 'REVIEW_ITEM'
  | 'REPURCHASE';

export interface RepurchaseResult {
  added: {
    productId: number;
    productName: string;
    addedQuantity: number;
    cartQuantity: number;
    unitPrice: number;
  }[];
  skipped: {
    productId: number;
    productName: string;
    requestedQuantity: number;
    reason: 'UNLISTED' | 'SOLD_OUT' | 'INSUFFICIENT_STOCK' | 'CART_LIMIT';
    additionalAvailable: number | null;
  }[];
}

export interface PurchaseOrder {
  inventoryStatus?: string;
  shipmentVersion?: number;
  id: number;
  orderNumber: string;
  status: string;
  totalAmount: number;
  productSubtotal?: number;
  shippingFee?: number;
  discountAmount?: number;
  currency: string;
  createdAt: string;
  paymentDueAt?: string;
  cancelledAt?: string;
  cancelReason?: string;
  receivedAt?: string;
  completedAt?: string;
  logisticsDeliveredAt?: string;
  confirmedAt?: string;
  shippedAt?: string;
  carrier?: string;
  trackingNumber?: string;
  availableActions: OrderAction[];
  items: PurchaseOrderItem[];
  address: OrderAddress;
  events?: OrderEvent[];
  payment?: OrderPayment;
  logistics?: OrderLogistics;
  refund?: OrderRefund;
}

export interface OrderSearchCriteria {
  q: string;
  status: string;
  from: string;
  to: string;
  page: number;
}

export interface OrderSearchPage {
  orders: PurchaseOrder[];
  total: number;
  page: number;
  size: number;
  pendingConfirm: number;
  pendingShip: number;
}

export const initialOrderSearch: OrderSearchCriteria = { q: '', status: '', from: '', to: '', page: 0 };

export const orderSearchStatuses: readonly string[] = [
  'PENDING_PAYMENT',
  'PAID',
  'CONFIRMED',
  'SHIPPED',
  'RECEIVED',
  'COMPLETED',
  'CANCELLING',
  'CANCELLED',
  'REFUNDING',
  'REFUNDED',
];

export const orderDetailSections = ['payment', 'refund', 'logistics', 'receipt', 'cancellation'] as const;
export type OrderDetailSection = (typeof orderDetailSections)[number];

export function notificationOrderSection(type: string): OrderDetailSection | undefined {
  if (type.startsWith('PAYMENT_') || type === 'ORDER_PAID') return 'payment';
  if (type.startsWith('REFUND_') || type === 'ORDER_REFUNDED') return 'refund';
  if (type.startsWith('LOGISTICS_') || type.startsWith('SHIPMENT_') || type === 'ORDER_SHIPPED') return 'logistics';
  if (['ORDER_RECEIVED', 'ORDER_AUTO_RECEIVED', 'ORDER_COMPLETED', 'ORDER_AUTO_COMPLETED'].includes(type))
    return 'receipt';
  return type === 'ORDER_CANCELLED' ? 'cancellation' : undefined;
}
