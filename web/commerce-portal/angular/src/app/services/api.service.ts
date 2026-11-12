import {HttpClient} from '@angular/common/http';
import {Injectable} from '@angular/core';
import {Observable} from 'rxjs';
import {CreateOrderRequest} from 'src/model/IOrder';
import {OrderList} from 'src/model/IOrderList';
import {Product} from 'src/model/IProduct';
import {IUser} from 'src/model/IUser';
import {ShippingAddress, ShippingAddressRequest} from 'src/model/IShippingAddress';
import {OrderLogistics, OrderRefund, PaymentChannel, PurchaseOrder} from 'src/model/IPurchaseOrder';
import {ScheduledTask, ScheduledTaskEvent, SchedulingPolicy, SchedulingPolicyEvent} from 'src/model/IScheduling';
import {LogisticsCallbackAuditEvent, LogisticsCallbackOutbox} from 'src/model/ILogisticsCallbackAdmin';
import {CustomerNotification} from 'src/model/INotification';
import {InventoryMovement, MerchantInventoryItem} from 'src/model/IInventory';

@Injectable({providedIn: 'root'})
export class ApiService {
  constructor(private http: HttpClient) {
  }

  allProducts(): Observable<Product[]> {
    return this.http.get<Product[]>(`/api/products?lang=${encodeURIComponent(localStorage.getItem('commerceflow-locale') || 'en')}`);
  }

  addOrder(productId: number, quantity: number): Observable<OrderList> {
    const request: CreateOrderRequest = {productId, quantity, note: 'Created from the Angular demo'};
    return this.http.post<OrderList>('/api/cart/items', request);
  }

  getOrders(): Observable<OrderList[]> {
    return this.http.get<OrderList[]>('/api/cart');
  }

  updateOrderQuantity(orderId: number, quantity: number): Observable<OrderList> {
    return this.http.put<OrderList>(`/api/cart/items/${orderId}`, {quantity});
  }

  removeCartItem(itemId: number): Observable<void> {
    return this.http.delete<void>(`/api/cart/items/${itemId}`);
  }

  clearCart(): Observable<void> {
    return this.http.delete<void>('/api/cart');
  }

  getUsers(): Observable<IUser[]> {
    return this.http.get<IUser[]>('/api/users');
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

  checkout(): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>('/api/purchase-orders/checkout', {});
  }

  purchaseOrders(): Observable<PurchaseOrder[]> {
    return this.http.get<PurchaseOrder[]>('/api/purchase-orders');
  }

  purchaseOrder(id: number): Observable<PurchaseOrder> {
    return this.http.get<PurchaseOrder>(`/api/purchase-orders/${id}`);
  }

  orderLogistics(id: number): Observable<OrderLogistics> {
    return this.http.get<OrderLogistics>(`/api/purchase-orders/${id}/logistics`);
  }

  payOrder(id: number, idempotencyKey: string, simulateFailure = false, channel: PaymentChannel = 'ALIPAY'): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/purchase-orders/${id}/payments`, {
      idempotencyKey,
      simulateFailure,
      channel
    });
  }

  cancelOrder(id: number, reason = ''): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/purchase-orders/${id}/cancel`, {reason});
  }

  notifications(): Observable<CustomerNotification[]> {
    return this.http.get<CustomerNotification[]>('/api/notifications');
  }

  unreadNotificationCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>('/api/notifications/unread-count');
  }

  markNotificationRead(id: number): Observable<void> {
    return this.http.patch<void>(`/api/notifications/${id}/read`, {});
  }

  markAllNotificationsRead(): Observable<void> {
    return this.http.patch<void>('/api/notifications/read-all', {});
  }

  merchantOrders(): Observable<PurchaseOrder[]> {
    return this.http.get<PurchaseOrder[]>('/api/merchant/orders');
  }

  merchantInventory(): Observable<MerchantInventoryItem[]> {
    return this.http.get<MerchantInventoryItem[]>('/api/merchant/inventory');
  }

  merchantInventoryMovements(productId: number): Observable<InventoryMovement[]> {
    return this.http.get<InventoryMovement[]>(`/api/merchant/inventory/${productId}/movements`);
  }

  adjustMerchantInventory(productId: number, delta: number, reason: string): Observable<MerchantInventoryItem> {
    return this.http.post<MerchantInventoryItem>(`/api/merchant/inventory/${productId}/adjustments`, {delta, reason});
  }

  confirmMerchantOrder(id: number): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/merchant/orders/${id}/confirm`, {});
  }

  shipMerchantOrder(id: number, carrier: string, trackingNumber: string): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/merchant/orders/${id}/ship`, {carrier, trackingNumber});
  }

  updateMerchantShipment(id: number, carrier: string, trackingNumber: string): Observable<PurchaseOrder> {
    return this.http.put<PurchaseOrder>(`/api/merchant/orders/${id}/shipment`, {carrier, trackingNumber});
  }

  receiveOrder(id: number): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/purchase-orders/${id}/receive`, {});
  }

  reviewOrderItem(id: number, rating: number, comment: string): Observable<unknown> {
    return this.http.post(`/api/order-items/${id}/reviews`, {rating, comment});
  }

  requestRefund(id: number, idempotencyKey: string, reasonCode: string, reasonDetail: string): Observable<OrderRefund> {
    return this.http.post<OrderRefund>(`/api/purchase-orders/${id}/refunds`, {
      idempotencyKey,
      reasonCode,
      reasonDetail
    });
  }

  schedulingPolicies(): Observable<SchedulingPolicy[]> {
    return this.http.get<SchedulingPolicy[]>('/api/admin/scheduling/policies');
  }

  saveSchedulingPolicy(policy: SchedulingPolicy): Observable<SchedulingPolicy> {
    return this.http.put<SchedulingPolicy>(`/api/admin/scheduling/policies/${encodeURIComponent(policy.taskType)}`, policy);
  }

  resetSchedulingPolicy(taskType: string): Observable<SchedulingPolicy> {
    return this.http.delete<SchedulingPolicy>(`/api/admin/scheduling/policies/${encodeURIComponent(taskType)}`);
  }

  schedulingPolicyEvents(taskType: string): Observable<SchedulingPolicyEvent[]> {
    return this.http.get<SchedulingPolicyEvent[]>(`/api/admin/scheduling/policies/${encodeURIComponent(taskType)}/events`);
  }

  scheduledTasks(taskType = '', status = ''): Observable<ScheduledTask[]> {
    const query = new URLSearchParams();
    if (taskType) query.set('taskType', taskType);
    if (status) query.set('status', status);
    query.set('limit', '200');
    return this.http.get<ScheduledTask[]>(`/api/admin/scheduling/tasks?${query}`);
  }

  scheduledTaskEvents(id: number): Observable<ScheduledTaskEvent[]> {
    return this.http.get<ScheduledTaskEvent[]>(`/api/admin/scheduling/tasks/${id}/events`);
  }

  retryScheduledTask(id: number): Observable<ScheduledTask> {
    return this.http.post<ScheduledTask>(`/api/admin/scheduling/tasks/${id}/retry`, {});
  }

  cancelScheduledTask(id: number, reason: string): Observable<ScheduledTask> {
    return this.http.post<ScheduledTask>(`/api/admin/scheduling/tasks/${id}/cancel`, {reason});
  }

  logisticsCallbacks(status = ''): Observable<LogisticsCallbackOutbox[]> {
    const query = status ? `?status=${encodeURIComponent(status)}` : '';
    return this.http.get<LogisticsCallbackOutbox[]>(`/api/admin/logistics-callbacks${query}`);
  }

  logisticsCallbackEvents(id: number): Observable<LogisticsCallbackAuditEvent[]> {
    return this.http.get<LogisticsCallbackAuditEvent[]>(`/api/admin/logistics-callbacks/${id}/events`);
  }

  replayLogisticsCallback(id: number, reason: string): Observable<LogisticsCallbackOutbox> {
    return this.http.post<LogisticsCallbackOutbox>(`/api/admin/logistics-callbacks/${id}/replay`, {reason});
  }
}
