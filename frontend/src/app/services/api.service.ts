import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateOrderRequest } from 'src/model/IOrder';
import { OrderList } from 'src/model/IOrderList';
import { Product } from 'src/model/IProduct';
import { IUser } from 'src/model/IUser';
import { ShippingAddress, ShippingAddressRequest } from 'src/model/IShippingAddress';
import { PaymentChannel, PurchaseOrder } from 'src/model/IPurchaseOrder';

@Injectable({ providedIn: 'root' })
export class ApiService {
  constructor(private http: HttpClient) {
  }

  allProducts(): Observable<Product[]> {
    return this.http.get<Product[]>('/api/products');
  }

  addOrder(productId: number, quantity: number): Observable<OrderList> {
    const request: CreateOrderRequest = { productId, quantity, note: 'Created from the Angular demo' };
    return this.http.post<OrderList>('/api/cart/items', request);
  }

  getOrders(): Observable<OrderList[]> {
    return this.http.get<OrderList[]>('/api/cart');
  }

  updateOrderQuantity(orderId: number, quantity: number): Observable<OrderList> {
    return this.http.put<OrderList>(`/api/cart/items/${orderId}`, { quantity });
  }

  removeCartItem(itemId: number): Observable<void> { return this.http.delete<void>(`/api/cart/items/${itemId}`); }
  clearCart(): Observable<void> { return this.http.delete<void>('/api/cart'); }

  getUsers(): Observable<IUser[]> {
    return this.http.get<IUser[]>('/api/users');
  }
  getAddresses(): Observable<ShippingAddress[]> { return this.http.get<ShippingAddress[]>('/api/addresses'); }
  createAddress(value: ShippingAddressRequest): Observable<ShippingAddress> { return this.http.post<ShippingAddress>('/api/addresses', value); }
  updateAddress(id: number, value: ShippingAddressRequest): Observable<ShippingAddress> { return this.http.put<ShippingAddress>(`/api/addresses/${id}`, value); }
  setDefaultAddress(id: number): Observable<ShippingAddress> { return this.http.put<ShippingAddress>(`/api/addresses/${id}/default`, {}); }
  selectAddress(id: number): Observable<ShippingAddress> { return this.http.put<ShippingAddress>(`/api/addresses/${id}/select`, {}); }
  selectedAddress(): Observable<{addressId?: number}> { return this.http.get<{addressId?: number}>('/api/addresses/selected'); }
  deleteAddress(id: number): Observable<void> { return this.http.delete<void>(`/api/addresses/${id}`); }
  checkout(): Observable<PurchaseOrder> { return this.http.post<PurchaseOrder>('/api/purchase-orders/checkout', {}); }
  purchaseOrders(): Observable<PurchaseOrder[]> { return this.http.get<PurchaseOrder[]>('/api/purchase-orders'); }
  purchaseOrder(id:number): Observable<PurchaseOrder> { return this.http.get<PurchaseOrder>(`/api/purchase-orders/${id}`); }
  payOrder(id:number, idempotencyKey:string, simulateFailure=false, channel:PaymentChannel='ALIPAY'): Observable<PurchaseOrder> {
    return this.http.post<PurchaseOrder>(`/api/purchase-orders/${id}/payments`, {idempotencyKey, simulateFailure, channel});
  }
  merchantOrders():Observable<PurchaseOrder[]>{return this.http.get<PurchaseOrder[]>('/api/merchant/orders');}
  confirmMerchantOrder(id:number):Observable<PurchaseOrder>{return this.http.post<PurchaseOrder>(`/api/merchant/orders/${id}/confirm`,{});}
  shipMerchantOrder(id:number,carrier:string,trackingNumber:string):Observable<PurchaseOrder>{return this.http.post<PurchaseOrder>(`/api/merchant/orders/${id}/ship`,{carrier,trackingNumber});}
  receiveOrder(id:number):Observable<PurchaseOrder>{return this.http.post<PurchaseOrder>(`/api/purchase-orders/${id}/receive`,{});}
  reviewOrderItem(id:number,rating:number,comment:string):Observable<unknown>{return this.http.post(`/api/order-items/${id}/reviews`,{rating,comment});}
}
