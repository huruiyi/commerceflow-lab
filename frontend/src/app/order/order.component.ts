import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { OrderList } from 'src/model/IOrderList';
import { ApiService } from '../services/api.service';
import { SeoService } from '../services/seo.service';
import { Router } from '@angular/router';
import { I18nService } from '../services/i18n.service';
import { forkJoin } from 'rxjs';

@Component({
  standalone: false,
  selector: 'app-order',
  templateUrl: './order.component.html',
  styleUrls: ['./order.component.css']
})
export class OrderComponent implements OnInit {

  customerOrders: OrderList[] = []
  updatingOrderIds = new Set<number>()
  checkoutError = ''
  checkingOut = false


  constructor(private api: ApiService, private seoService: SeoService, private router: Router,
              private changeDetector: ChangeDetectorRef, private i18n: I18nService, private destroyRef: DestroyRef) {
    seoService.setTitle(i18n.t('shoppingCart'))
    seoService.setMeta(i18n.t('shoppingCart'))
  }

  ngOnInit(): void {
    this.loadCart()
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.loadCart())
  }

  private loadCart(): void {
    forkJoin({orders: this.api.getOrders(), products: this.api.allProducts()}).subscribe({
        next: ({orders, products}) => {
          const names = new Map(products.map(product => [product.id, product.name]))
          this.customerOrders = orders.map(order => ({...order, productName: names.get(order.productId) ?? order.productName}))
          this.changeDetector.markForCheck()
        },
        error: err => {
          console.log(err)
        },
      })
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

  checkout(): void {
    this.checkingOut=true; this.checkoutError='';
    this.api.checkout().subscribe({next:()=>void this.router.navigate(['/orders']),error:err=>{this.checkoutError=err.error?.message ?? this.i18n.t('checkoutFailed');this.checkingOut=false;this.changeDetector.markForCheck();},complete:()=>{this.checkingOut=false;this.changeDetector.markForCheck();}});
  }

  private normalizeQuantity(quantity: number): number {
    if (!Number.isFinite(quantity)) {
      return 1
    }
    return Math.min(99, Math.max(1, Math.trunc(quantity)))
  }

}
