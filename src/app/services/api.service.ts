import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { baseUrl, ref } from 'src/environments/environment';
import { IOrder } from 'src/model/IOrder';
import { IOrderList } from 'src/model/IOrderList';
import { IProduct } from 'src/model/IProduct';
import { IUser } from 'src/model/IUser';
import { AccountService } from './account.service';

@Injectable({
  providedIn: 'root'
})
export class ApiService {

  constructor(private http: HttpClient, private accountService :AccountService) { }

  //All Product
  allProduct() {
    const params = new HttpParams()
      .set('ref', ref)
      .set('start', 0)

    return this.http.get<IProduct>(baseUrl + 'product.php', { params: params })
  }

  //Add basket
  addOrder(productId :string) {
    //https://www.jsonbulut.com/json/orderForm.php?ref=d1becef32825e5c8b0fc1b096230400b&customerId=12&productId=12&html=12
    const userInfo = this.accountService.loginControl()

    if (userInfo){
      const params = new HttpParams()
      .set('ref', ref)
      .set('customerId', userInfo.userId)
      .set('productId', productId)
      .set('html', 'lorem ipsum')

      return this.http.get<IOrder>(baseUrl + 'orderForm.php', { params: params })
    }

    return null
  }

  //https://www.jsonbulut.com/json/orderList.php?ref=d1becef32825e5c8b0fc1b096230400b&musterilerID=3056
  getOrders() {
    const userInfo = this.accountService.loginControl()

    if (userInfo){
      const params = new HttpParams()
      .set('ref', ref)
      .set('musterilerID', userInfo.userId)
      .set('random', Math.random()) //cachelemeyi kandırmak içindir.

      return this.http.get<IOrderList>(baseUrl + 'orderList.php', { params: params })
    }

    return null
  }

  //https://jsonplaceholder.typicode.com/users
  getUsers() {
    return this.http.get<IUser[]>("https://jsonplaceholder.typicode.com/users")
  }

}
