import { HttpClient } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { CreateOrderRequest } from 'src/model/IOrder';
import { OrderList } from 'src/model/IOrderList';
import { Product } from 'src/model/IProduct';
import { IUser } from 'src/model/IUser';

@Injectable({ providedIn: 'root' })
export class ApiService {
  constructor(private http: HttpClient) {
  }

  allProducts(): Observable<Product[]> {
    return this.http.get<Product[]>('/api/products');
  }

  addOrder(productId: number, quantity: number): Observable<OrderList> {
    const request: CreateOrderRequest = { productId, quantity, note: 'Created from the Angular demo' };
    return this.http.post<OrderList>('/api/orders', request);
  }

  getOrders(): Observable<OrderList[]> {
    return this.http.get<OrderList[]>('/api/orders');
  }

  updateOrderQuantity(orderId: number, quantity: number): Observable<OrderList> {
    return this.http.put<OrderList>(`/api/orders/${orderId}/quantity`, { quantity });
  }

  getUsers(): Observable<IUser[]> {
    return this.http.get<IUser[]>('/api/users');
  }
}
