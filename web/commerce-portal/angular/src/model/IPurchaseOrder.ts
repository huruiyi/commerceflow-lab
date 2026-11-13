export type PaymentChannel = 'ALIPAY' | 'WECHAT_PAY' | 'UNION_PAY';

export interface PurchaseOrderItem {
  id: number;
  productId: number;
  productName: string;
  thumbnailUrl: string;
  unitPrice: number;
  quantity: number;
  subtotal: number;
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
  | 'REVIEW_ITEM';

export interface PurchaseOrder {
  id: number;
  orderNumber: string;
  status: string;
  totalAmount: number;
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
