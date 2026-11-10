import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
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
  updatingOrderIds = new Set<number>()


  constructor(private api: ApiService, private seoService: SeoService,
              private changeDetector: ChangeDetectorRef) {
    seoService.setTitle("Order Page")
    seoService.setMeta("The page that lists users orders...")

    api.getOrders().subscribe({
        next: res => {
          this.customerOrders = res
          this.changeDetector.markForCheck()
        },
        error: err => {
          console.log(err)
        },
      })
  }

  ngOnInit(): void {
  }

  changeQuantity(order: OrderList, quantity: number): void {
    const normalized = this.normalizeQuantity(quantity)
    if (normalized === order.quantity || this.updatingOrderIds.has(order.id)) {
      return
    }

    this.updatingOrderIds.add(order.id)
    this.api.updateOrderQuantity(order.id, normalized).subscribe({
      next: updated => {
        this.customerOrders = this.customerOrders.map(item => item.id === updated.id ? updated : item)
        this.changeDetector.markForCheck()
      },
      error: err => {
        console.log(err)
        this.updatingOrderIds.delete(order.id)
        this.changeDetector.markForCheck()
      },
      complete: () => {
        this.updatingOrderIds.delete(order.id)
        this.changeDetector.markForCheck()
      }
    })
  }

  quantityInput(order: OrderList, event: Event): void {
    this.changeQuantity(order, (event.target as HTMLInputElement).valueAsNumber)
  }

  remove(order: OrderList): void {
    this.api.removeCartItem(order.id).subscribe(() => { this.customerOrders = this.customerOrders.filter(i => i.id !== order.id); this.changeDetector.markForCheck(); });
  }

  clearCart(): void {
    this.api.clearCart().subscribe(() => { this.customerOrders = []; this.changeDetector.markForCheck(); });
  }

  private normalizeQuantity(quantity: number): number {
    if (!Number.isFinite(quantity)) {
      return 1
    }
    return Math.min(99, Math.max(1, Math.trunc(quantity)))
  }

}
