import { Component, OnInit } from '@angular/core';
import { OrderList } from 'src/model/IOrderList';
import { ApiService } from '../services/api.service';
import { SeoService } from '../services/seo.service';

@Component({
  standalone: false,
  selector: 'app-order',
  templateUrl: './order.component.html',
  styleUrls: ['./order.component.css']
})
export class OrderComponent implements OnInit {

  customerOrders: OrderList[] = []


  constructor(private api :ApiService, private seoService: SeoService) {
    seoService.setTitle("Order Page")
    seoService.setMeta("The page that lists users orders...")

    const apiResult = api.getOrders()
    if (apiResult) {
      const newThis = this
      apiResult.subscribe({
        next(res) {
          //console.log(res);
          newThis.customerOrders = res.orderList[0]
        },
        error(err) {
          console.log(err);
          return null
        },
        complete() {
          //console.log(newThis.customerOrders);

        },
      })
    }
  }

  ngOnInit(): void {
  }

}
