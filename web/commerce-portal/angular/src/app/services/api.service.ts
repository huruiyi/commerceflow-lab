import { AdminListPage } from 'src/model/IAdminListPage';
import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { forkJoin, map, Observable, of } from 'rxjs';
import { CreateOrderRequest } from 'src/model/IOrder';
import { OrderList } from 'src/model/IOrderList';
import { Product, ProductReviewPage } from 'src/model/IProduct';
import { IUser } from 'src/model/IUser';
import { ShippingAddress, ShippingAddressRequest } from 'src/model/IShippingAddress';
import {
  OrderLogistics,
  OrderRefund,
  OrderSearchCriteria,
  OrderSearchPage,
  PaymentChannel,
  PurchaseOrder,
  WarehousePlan,
  RepurchaseResult,
} from 'src/model/IPurchaseOrder';
import { ScheduledTask, ScheduledTaskEvent, SchedulingPolicy, SchedulingPolicyEvent } from 'src/model/IScheduling';
import { LogisticsCallbackAuditEvent, LogisticsCallbackOutbox } from 'src/model/ILogisticsCallbackAdmin';
import { CustomerNotification } from 'src/model/INotification';
import { InventoryMovement, MerchantInventoryItem } from 'src/model/IInventory';
import {
  InventoryRecovery,
  StocktakeBaseline,
  StocktakeProposal,
  StocktakeReview,
  WarehouseEvidenceContext,
  WarehouseEvidenceLine,
  WarehouseEvidenceReceipt,
} from 'src/model/IInventoryReconciliation';

export type FulfillmentOperation =
  | { operation: 'CONFIRM' }
  | {
      operation: 'SHIP';
      carrier: string;
      trackingNumber: string;
      lines: { orderLineId: number; quantity: number }[];
      warehouseId?: number;
    }
  | { operation: 'UPDATE_SHIPMENT'; carrier: string; trackingNumber: string; expectedShipmentVersion: number };

export type FulfillmentIntent = FulfillmentOperation & {
  commandId: string;
  orderId: number;
  occurredAt: string;
};

export interface FulfillmentReceipt {
  commandId: string;
  orderId: number;
  status: 'PENDING' | 'COMPLETED' | 'REJECTED' | 'REVIEW_REQUIRED';
  errorCode?: string;
}

const FULFILLMENT_COMMANDS = '/api/merchant/orders/fulfillment-v1/commands';

export interface AfterSaleOffer {
  kind: 'BEFORE_SHIPMENT' | 'RETURN' | 'EXCHANGE';
  availableQuantity: number;
  merchandiseAmount: number;
  shippingAmount: number;
}

export interface AfterSale extends Pick<AfterSaleOffer, 'merchandiseAmount' | 'shippingAmount'> {
  kind: AfterSaleOffer['kind'];
  orderId: number;
  orderLineId: number;
  productId: number;
  merchantId: number;
  warehouseId?: number;
  outboundId?: string;
  warehouseReturnStatus?: string;
  receivedQuantity?: number;
  approvedQuantity?: number;
  approvedMerchandiseAmount?: number;
  workOrderPhase?: string;
  id: string;
  status: string;
  reason: string;
  quantity: number;
  trackingNumber?: string;
  returnShippingCost?: number;
  refundNumber?: string;
  decision?: string;
}

export interface AfterSaleListCriteria {
  orderId?: number;
  caseId?: string;
  status?: string;
  createdFrom?: string;
  createdBefore?: string;
}

export interface AfterSalePage<T> {
  rows: T[];
  total: number;
  page: number;
  size: number;
}

export interface ReturnWorkTicket {
  kind: AfterSaleOffer['kind'];
  caseId: string;
  orderId: number;
  orderLineId: number;
  warehouseId: number;
  outboundId: string;
  trackingNumber: string;
  warehouseStatus: string;
  phase: string;
  caseStatus: string;
  requestedQuantity: number;
  receivedQuantity: number;
  approvedQuantity?: number;
  approvedAmount?: number;
  refundNumber?: string;
}

export interface ExchangeDetail {
  exchange: AfterSale;
  returnTracking: OrderLogistics | null;
  replacementTracking: OrderLogistics | null;
}

export interface ReturnWorkDetail {
  ticket: ReturnWorkTicket;
  actions: { requestId: string; actorId: number; action: string; note: string; createdAt: string }[];
}

export interface ReturnWorkEvidence {
  id: string;
  qualifiedQuantity: number;
  note: string;
}

export interface CouponCampaign {
  id: string;
  title: string;
  minimumAmount: number;
  discountAmount: number;
  supply: number;
  issued: number;
  startsAt?: string;
  expiresAt: string;
  enabled: boolean;
}

export interface CustomerCoupon {
  id: string;
  campaignId: string;
  title: string;
  minimumAmount: number;
  status: 'AVAILABLE' | 'HELD' | 'USED' | 'EXPIRED';
  enabled: boolean;
  expiresAt: string;
}

export interface CheckoutSelection {
  addressId: number;
  itemIds: number[];
  couponId?: string;
}

export interface CheckoutQuote {
  quoteId: string;
  productSubtotal: number;
  discountAmount: number;
  shippingFee: number;
  totalAmount: number;
}

export interface ShippingRate {
  countryCode: string;
  stateProvince: string;
  fee: number;
}

export interface ShippingTemplate {
  defaultFee: number;
  regions: ShippingRate[];
}

export interface MerchantProduct {
  productId: number;
  name: string;
  brief: string;
  description: string;
  imageUrl: string;
  thumbnailUrl: string;
  unitPrice: number;
  lifecycleStatus: 'ACTIVE' | 'INACTIVE';
  businessVersion: number;
}

export interface CatalogSearchPage {
  products: Product[];
  total: number;
  page: number;
  size: number;
}

export interface CatalogStore {
  merchantId: number;
  name: string;
}

export interface StockAvailability {
  productId: number;
  listed: boolean;
  available: number;
}

@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly stocktakes = '/api/admin/inventory-command-recoveries/stocktakes';

  constructor(private http: HttpClient) {}

  inventoryRecoveries(): Observable<InventoryRecovery[]> {
    return this.http.get<InventoryRecovery[]>('/api/admin/inventory-command-recoveries', {
      params: { limit: 200 },
    });
  }

  stocktakeBaseline(productId: number): Observable<StocktakeBaseline> {
    return this.http.get<StocktakeBaseline>(`${this.stocktakes}/baseline/${productId}`);
  }

  submitStocktake(proposal: StocktakeProposal): Observable<StocktakeReview> {
    return this.http.post<StocktakeReview>(this.stocktakes, proposal);
  }

  stocktakeReview(reference: string): Observable<StocktakeReview> {
    return this.http.get<StocktakeReview>(`${this.stocktakes}/${encodeURIComponent(reference)}`);
  }

  approveStocktake(reference: string, reason: string): Observable<StocktakeReview> {
    return this.http.post<StocktakeReview>(`${this.stocktakes}/${encodeURIComponent(reference)}/approve`, { reason });
  }

  warehouseEvidenceContext(commandId: string): Observable<WarehouseEvidenceContext> {
    return this.http.get<WarehouseEvidenceContext>(
      `/api/admin/inventory-command-recoveries/${encodeURIComponent(commandId)}/warehouse-evidence`,
    );
  }

  submitWarehouseEvidence(commandId: string, lines: WarehouseEvidenceLine[]): Observable<WarehouseEvidenceReceipt> {
    return this.http.post<WarehouseEvidenceReceipt>(
      `/api/admin/inventory-command-recoveries/${encodeURIComponent(commandId)}/warehouse-evidence`,
      { lines },
    );
  }

  searchProducts(params: Record<string, number | string>): Observable<CatalogSearchPage> {
    return this.http.get<CatalogSearchPage>('/api/products/search', {
      params: { ...params, lang: this.productLocale() },
    });
  }

  publicStores(): Observable<CatalogStore[]> {
    return this.http.get<CatalogStore[]>('/api/stores');
  }

  productDetail(id: number): Observable<Product> {
    return this.http.get<Product>(`/api/products/${id}`, { params: { lang: this.productLocale() } });
  }

  productReviews(id: number, page: number, size: number): Observable<ProductReviewPage> {
    return this.http.get<ProductReviewPage>(`/api/products/${id}/reviews`, { params: { page, size } });
  }

  private productLocale(): string {
    return localStorage.getItem('commerceflow-locale') || 'en';
  }

  allProducts(): Observable<Product[]> {
    return this.http.get<Product[]>(
      `/api/products?lang=${encodeURIComponent(localStorage.getItem('commerceflow-locale') || 'en')}`,
    );
  }

  cartProducts(ids: number[]): Observable<Record<number, Pick<Product, 'name' | 'price'>>> {
    const requests = [];
    const uniqueIds = [...new Set(ids)];
    for (let start = 0; start < uniqueIds.length; start += 100) {
      requests.push(
        this.http.post<Record<number, Pick<Product, 'name' | 'price'>>>(
          '/api/products/lookup',
          uniqueIds.slice(start, start + 100),
          { params: { lang: this.productLocale() } },
        ),
      );
    }
    return requests.length ? forkJoin(requests).pipe(map((batches) => Object.assign({}, ...batches))) : of({});
  }

  productAvailability(ids: number[]): Observable<Record<number, StockAvailability>> {
    const requests = [];
    for (let start = 0; start < ids.length; start += 200) {
      requests.push(
        this.http.post<Record<number, StockAvailability>>('/api/products/availability', ids.slice(start, start + 200)),
      );
    }
    return requests.length ? forkJoin(requests).pipe(map((batches) => Object.assign({}, ...batches))) : of({});
  }

  addOrder(productId: number, quantity: number, skuId?: number): Observable<OrderList> {
    const request: CreateOrderRequest = { productId, quantity, note: 'Created from the Angular demo' };
    if (skuId !== undefined) request.skuId = skuId;
    return this.http.post<OrderList>('/api/cart/items', request);
  }

  getOrders(): Observable<OrderList[]> {
    return this.http.get<OrderList[]>('/api/cart');
  }

  updateOrderQuantity(orderId: number, quantity: number): Observable<OrderList> {
    return this.http.put<OrderList>(`/api/cart/items/${orderId}`, { quantity });
  }

  removeCartItem(itemId: number): Observable<void> {
    return this.http.delete<void>(`/api/cart/items/${itemId}`);
  }

  clearCart(): Observable<void> {
    return this.http.delete<void>('/api/cart');
  }

  getUsers(q: string, page: number): Observable<AdminListPage<IUser>> {
    return this.http.get<AdminListPage<IUser>>('/api/users/page', { params: { q, page } });
  }

  getAddresses(): Observable<ShippingAddress[]> {
    return this.http.get<ShippingAddress[]>('/api/addresses');
  }

  createAddress(value: ShippingAddressRequest): Observable<ShippingAddress> {
    return this.http.post<ShippingAddress>('/api/addresses', value);
  }

  updateAddress(id: number, value: ShippingAddressRequest): Observable<ShippingAddress> {
    return this.http.put<ShippingAddress>(`/api/addresses/${id}`, value);
  }

  setDefaultAddress(id: number): Observable<ShippingAddress> {
    return this.http.put<ShippingAddress>(`/api/addresses/${id}/default`, {});
  }

  selectAddress(id: number): Observable<ShippingAddress> {
    return this.http.put<ShippingAddress>(`/api/addresses/${id}/select`, {});
  }

  selectedAddress(): Observable<{ addressId?: number }> {
    return this.http.get<{ addressId?: number }>('/api/addresses/selected');
  }

  deleteAddress(id: number): Observable<void> {
    return this.http.delete<void>(`/api/addresses/${id}`);
  }

  lineAfterSales(orderId: number, merchant: boolean): Observable<AfterSale[]> {
    const base = merchant ? '/api/merchant/orders' : '/api/purchase-orders';
    return this.http.get<AfterSale[]>(base + '/' + orderId + '/after-sales');
  }

  warehousePlan(orderId: number, merchant: boolean): Observable<WarehousePlan> {
    const base = merchant ? '/api/merchant/orders' : '/api/purchase-orders';
    return this.http.get<WarehousePlan>(`${base}/${orderId}/warehouse-plan`);
  }

  quoteAfterSale(
    orderId: number,
    orderLineId: number,
    quantity: number,
    warehouseId?: number,
    outboundId?: string,
    exchange?: boolean,
  ): Observable<AfterSaleOffer> {
    return this.http.post<AfterSaleOffer>('/api/purchase-orders/' + orderId + '/after-sales/quote', {
      orderLineId,
      quantity,
      warehouseId,
      outboundId,
      exchange,
    });
  }

  submitAfterSale(
    orderId: number,
    body: {
      orderLineId: number;
      quantity: number;
      reason: string;
      requestId: string;
      warehouseId?: number;
      outboundId?: string;
      exchange?: boolean;
    },
  ): Observable<AfterSale> {
    return this.http.post<AfterSale>('/api/purchase-orders/' + orderId + '/after-sales', body);
  }

  sendAfterSaleReturn(
    id: string,
    carrier: string,
    trackingNumber: string,
    returnShippingCost: number | null,
  ): Observable<AfterSale> {
    return this.http.post<AfterSale>('/api/purchase-orders/after-sales/' + id + '/return', {
      carrier,
      trackingNumber,
      returnShippingCost,
    });
  }

  afterSaleReturnTracking(id: string, merchant: boolean): Observable<OrderLogistics | null> {
    const path = merchant ? '/api/merchant/after-sales/' : '/api/purchase-orders/after-sales/';
    return this.http.get<OrderLogistics | null>(path + id + '/return-tracking');
  }

  afterSaleReplacementTracking(id: string, merchant: boolean): Observable<OrderLogistics | null> {
    const path = merchant ? '/api/merchant/after-sales/' : '/api/purchase-orders/after-sales/';
    return this.http.get<OrderLogistics | null>(path + id + '/replacement-tracking');
  }

  shipExchangeReplacement(id: string, carrier: string, trackingNumber: string): Observable<AfterSale> {
    return this.http.post<AfterSale>('/api/merchant/after-sales/' + id + '/replacement', {
      carrier,
      trackingNumber,
    });
  }

  platformExchanges(criteria: AfterSaleListCriteria, page: number): Observable<AfterSalePage<AfterSale>> {
    return this.http.get<AfterSalePage<AfterSale>>('/api/admin/exchanges', { params: { ...criteria, page } });
  }

  platformExchange(id: string): Observable<ExchangeDetail> {
    return this.http.get<ExchangeDetail>('/api/admin/exchanges/' + id);
  }

  decideAfterSale(id: string, inspection: boolean, body: { accepted: boolean; note: string }): Observable<AfterSale> {
    return this.http.post<AfterSale>(
      '/api/merchant/after-sales/' + id + (inspection ? '/inspection' : '/review'),
      body,
    );
  }

  returnWorkOrders(criteria: AfterSaleListCriteria, page: number): Observable<AfterSalePage<ReturnWorkTicket>> {
    return this.http.get<AfterSalePage<ReturnWorkTicket>>('/api/merchant/return-work-orders', {
      params: { ...criteria, page },
    });
  }

  returnWorkOrder(caseId: string, merchantId?: number): Observable<ReturnWorkDetail> {
    return this.http.get<ReturnWorkDetail>(this.returnWorkPath(caseId, merchantId));
  }

  returnWorkEvidence(caseId: string, merchantId?: number): Observable<ReturnWorkEvidence | null> {
    return this.http.get<ReturnWorkEvidence | null>(this.returnWorkPath(caseId, merchantId) + '/evidence');
  }

  private returnWorkPath(caseId: string, merchantId?: number): string {
    const prefix = merchantId === undefined ? '/api/merchant' : `/api/admin/stores/${merchantId}`;
    return prefix + '/return-work-orders/' + encodeURIComponent(caseId);
  }

  decideReturnWork(
    caseId: string,
    body: { requestId: string; action: string; note: string; quantity?: number },
  ): Observable<ReturnWorkDetail> {
    return this.http.post<ReturnWorkDetail>('/api/merchant/return-work-orders/' + caseId + '/actions', body);
  }

  couponCampaigns(merchant: boolean): Observable<CouponCampaign[]> {
    return this.http.get<CouponCampaign[]>(merchant ? '/api/merchant/coupon-campaigns' : '/api/coupons/campaigns');
  }

  couponWallet(): Observable<CustomerCoupon[]> {
    return this.http.get<CustomerCoupon[]>('/api/coupons');
  }

  createCouponCampaign(
    command: Pick<CouponCampaign, 'title' | 'minimumAmount' | 'discountAmount' | 'supply' | 'expiresAt'> & {
      startsAt: string;
    },
  ): Observable<CouponCampaign> {
    return this.http.post<CouponCampaign>('/api/merchant/coupon-campaigns', command);
  }

  disableCouponCampaign(id: string): Observable<void> {
    return this.http.post<void>('/api/merchant/coupon-campaigns/' + id + '/disable', {});
  }

  claimCoupon(id: string): Observable<CustomerCoupon> {
    return this.http.post<CustomerCoupon>('/api/coupons/campaigns/' + id + '/claim', {});
  }

  checkoutQuote(selection: CheckoutSelection): Observable<CheckoutQuote> {
    return this.http.post<CheckoutQuote>('/api/purchase-orders/checkout/quote', selection);
  }

  checkout(selection: CheckoutSelection, quoteId: string): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>('/api/purchase-orders/checkout', { selection, quoteId });
  }

  shippingTemplate(): Observable<ShippingTemplate> {
    return this.http.get<ShippingTemplate>('/api/merchant/shipping-template');
  }

  saveShippingTemplate(template: ShippingTemplate): Observable<ShippingTemplate> {
    return this.http.put<ShippingTemplate>('/api/merchant/shipping-template', template);
  }

  searchOrders(criteria: OrderSearchCriteria, merchant = false): Observable<OrderSearchPage> {
    const { q, status, from, to, page } = criteria;
    const params: Record<string, string | number> = {
      page,
      q,
      status,
      lang: localStorage.getItem('commerceflow-locale') || 'en',
      zone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    };
    if (from) params['from'] = from;
    if (to) params['to'] = to;
    return this.http.get<OrderSearchPage>(merchant ? '/api/merchant/orders/search' : '/api/purchase-orders/search', {
      params,
    });
  }

  purchaseOrder(id: number): Observable<PurchaseOrder> {
    return this.http.get<PurchaseOrder>(`/api/purchase-orders/${id}`);
  }

  repurchase(id: number, requestId: string): Observable<RepurchaseResult> {
    return this.http.post<RepurchaseResult>(`/api/purchase-orders/${id}/repurchase`, { requestId });
  }

  orderLogistics(id: number): Observable<OrderLogistics> {
    return this.http.get<OrderLogistics>(`/api/purchase-orders/${id}/logistics`);
  }

  payOrder(
    id: number,
    idempotencyKey: string,
    simulateFailure = false,
    channel: PaymentChannel = 'ALIPAY',
  ): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/purchase-orders/${id}/payments`, {
      idempotencyKey,
      simulateFailure,
      channel,
    });
  }

  cancelOrder(id: number, reason = ''): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/purchase-orders/${id}/cancel`, { reason });
  }

  notifications(): Observable<CustomerNotification[]> {
    return this.http.get<CustomerNotification[]>('/api/notifications');
  }

  unreadNotificationCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>('/api/notifications/unread-count');
  }

  markNotificationRead(eventId: string): Observable<void> {
    return this.http.patch<void>(`/api/notifications/${encodeURIComponent(eventId)}/read`, {});
  }

  markAllNotificationsRead(): Observable<void> {
    return this.http.patch<void>('/api/notifications/read-all', {});
  }

  merchantInventory(): Observable<MerchantInventoryItem[]> {
    return this.http.get<MerchantInventoryItem[]>('/api/merchant/inventory');
  }

  merchantProducts(): Observable<MerchantProduct[]> {
    return this.http.get<MerchantProduct[]>('/api/merchant/catalog/v1/products');
  }

  createMerchantProduct(request: object): Observable<MerchantProduct> {
    return this.http.post<MerchantProduct>('/api/merchant/catalog/v1/products', request);
  }

  updateMerchantProduct(id: number, area: 'profile' | 'price' | 'status', value: object): Observable<MerchantProduct> {
    return this.http.put<MerchantProduct>(`/api/merchant/catalog/v1/products/${id}/${area}`, value);
  }

  merchantInventoryMovements(productId: number, page: number): Observable<AdminListPage<InventoryMovement>> {
    return this.http.get<AdminListPage<InventoryMovement>>(`/api/merchant/inventory/${productId}/movements/page`, {
      params: { page },
    });
  }

  adjustMerchantInventory(
    productId: number,
    delta: number,
    reason: string,
    warehouseId?: number,
  ): Observable<MerchantInventoryItem> {
    return this.http.post<MerchantInventoryItem>(`/api/merchant/inventory/${productId}/adjustments`, {
      delta,
      reason,
      warehouseId,
    });
  }

  merchantShipmentStatus(id: number): Observable<{ status: string; step: string; lastError?: string }> {
    return this.http.get<{ status: string; step: string; lastError?: string }>(
      `/api/merchant/orders/${id}/fulfillment-v1/shipment`,
    );
  }

  submitFulfillment(intent: FulfillmentIntent): Observable<FulfillmentReceipt> {
    if (intent.operation === 'SHIP') {
      const { orderId, commandId, carrier, trackingNumber, lines, warehouseId } = intent;
      return this.http.post<FulfillmentReceipt>(`/api/merchant/orders/${orderId}/packages`, {
        packageId: commandId,
        carrier,
        trackingNumber,
        lines,
        warehouseId,
      });
    }
    return this.http.post<FulfillmentReceipt>(FULFILLMENT_COMMANDS, { version: 1, ...intent });
  }

  fulfillmentReceipt(commandId: string): Observable<FulfillmentReceipt> {
    return this.http.get<FulfillmentReceipt>(`${FULFILLMENT_COMMANDS}/${encodeURIComponent(commandId)}`);
  }

  receiveOrder(id: number): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/purchase-orders/${id}/receive`, {});
  }

  reviewOrderItem(id: number, rating: number, comment: string): Observable<unknown> {
    return this.http.post(`/api/order-items/${id}/reviews`, { rating, comment });
  }

  requestRefund(id: number, idempotencyKey: string, reasonCode: string, reasonDetail: string): Observable<OrderRefund> {
    return this.http.post<OrderRefund>(`/api/purchase-orders/${id}/refunds`, {
      idempotencyKey,
      reasonCode,
      reasonDetail,
    });
  }

  schedulingPolicies(): Observable<SchedulingPolicy[]> {
    return this.http.get<SchedulingPolicy[]>('/api/admin/scheduling/policies');
  }

  saveSchedulingPolicy(policy: SchedulingPolicy): Observable<SchedulingPolicy> {
    return this.http.put<SchedulingPolicy>(
      `/api/admin/scheduling/policies/${encodeURIComponent(policy.taskType)}`,
      policy,
    );
  }

  resetSchedulingPolicy(taskType: string): Observable<SchedulingPolicy> {
    return this.http.delete<SchedulingPolicy>(`/api/admin/scheduling/policies/${encodeURIComponent(taskType)}`);
  }

  schedulingPolicyEvents(taskType: string): Observable<SchedulingPolicyEvent[]> {
    return this.http.get<SchedulingPolicyEvent[]>(
      `/api/admin/scheduling/policies/${encodeURIComponent(taskType)}/events`,
    );
  }

  scheduledTasks(taskType: string, status: string, page: number): Observable<AdminListPage<ScheduledTask>> {
    return this.http.get<AdminListPage<ScheduledTask>>('/api/admin/scheduling/tasks/page', {
      params: { taskType, status, page },
    });
  }

  scheduledTaskEvents(id: number): Observable<ScheduledTaskEvent[]> {
    return this.http.get<ScheduledTaskEvent[]>(`/api/admin/scheduling/tasks/${id}/events`);
  }

  retryScheduledTask(id: number): Observable<ScheduledTask> {
    return this.http.post<ScheduledTask>(`/api/admin/scheduling/tasks/${id}/retry`, {});
  }

  cancelScheduledTask(id: number, reason: string): Observable<ScheduledTask> {
    return this.http.post<ScheduledTask>(`/api/admin/scheduling/tasks/${id}/cancel`, { reason });
  }

  logisticsCallbacks(status: string, page: number): Observable<AdminListPage<LogisticsCallbackOutbox>> {
    return this.http.get<AdminListPage<LogisticsCallbackOutbox>>('/api/admin/logistics-callbacks/page', {
      params: { status, page },
    });
  }

  logisticsCallbackEvents(id: number): Observable<LogisticsCallbackAuditEvent[]> {
    return this.http.get<LogisticsCallbackAuditEvent[]>(`/api/admin/logistics-callbacks/${id}/events`);
  }

  replayLogisticsCallback(id: number, reason: string): Observable<LogisticsCallbackOutbox> {
    return this.http.post<LogisticsCallbackOutbox>(`/api/admin/logistics-callbacks/${id}/replay`, { reason });
  }
}
