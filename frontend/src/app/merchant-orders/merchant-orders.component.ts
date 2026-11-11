import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { forkJoin } from 'rxjs';
import { PurchaseOrder } from 'src/model/IPurchaseOrder';
import { ApiService } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-merchant-orders',
  templateUrl: './merchant-orders.component.html',
  styleUrls: ['./merchant-orders.component.css']
})
export class MerchantOrdersComponent implements OnInit {
  orders: PurchaseOrder[] = [];
  carrier: Record<number, string> = {};
  tracking: Record<number, string> = {};
  expandedOrders = new Set<number>();
  notice = '';

  constructor(
    private api: ApiService,
    private i18n: I18nService,
    private changeDetector: ChangeDetectorRef,
    private destroyRef: DestroyRef
  ) {}

  ngOnInit(): void {
    this.load();
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.load());
  }

  load(): void {
    forkJoin({ orders: this.api.merchantOrders(), products: this.api.allProducts() }).subscribe(({ orders, products }) => {
      const names = new Map(products.map(product => [product.id, product.name]));
      this.orders = orders.map(order => ({
        ...order,
        items: order.items.map(item => ({ ...item, productName: names.get(item.productId) ?? item.productName }))
      }));
      this.orders.forEach(order => {
        if (order.carrier) this.carrier[order.id] = order.carrier;
        if (order.trackingNumber) this.tracking[order.id] = order.trackingNumber;
      });
      this.changeDetector.markForCheck();
    });
  }

  confirm(order: PurchaseOrder): void {
    this.api.confirmMerchantOrder(order.id).subscribe(value => {
      this.replace(value);
      this.notice = this.i18n.t('orderConfirmed', { order: value.orderNumber });
    });
  }

  saveShipment(order: PurchaseOrder): void {
    if (this.shipmentInvalid(order) || this.shipmentUnchanged(order)) return;
    const request = order.status === 'SHIPPED'
      ? this.api.updateMerchantShipment(order.id, this.carrier[order.id].trim(), this.tracking[order.id].trim())
      : this.api.shipMerchantOrder(order.id, this.carrier[order.id].trim(), this.tracking[order.id].trim());
    request.subscribe(value => {
      this.replace(value);
      this.notice = this.i18n.t(order.status === 'SHIPPED' ? 'shipmentUpdated' : 'orderShipped', { order: value.orderNumber });
    });
  }

  shipmentInvalid(order: PurchaseOrder): boolean {
    return !(this.carrier[order.id] || '').trim() || !(this.tracking[order.id] || '').trim();
  }

  shipmentUnchanged(order: PurchaseOrder): boolean {
    return order.status === 'SHIPPED'
      && (this.carrier[order.id] || '').trim() === (order.carrier || '').trim()
      && (this.tracking[order.id] || '').trim() === (order.trackingNumber || '').trim();
  }

  toggleDetails(orderId: number): void {
    this.expandedOrders.has(orderId) ? this.expandedOrders.delete(orderId) : this.expandedOrders.add(orderId);
  }

  prepareShipment(order: PurchaseOrder): void {
    if (order.carrier) this.carrier[order.id] = order.carrier;
    if (order.trackingNumber) this.tracking[order.id] = order.trackingNumber;
    this.expandedOrders.add(order.id);
  }

  async copyOrderNumber(order: PurchaseOrder): Promise<void> {
    await navigator.clipboard.writeText(order.orderNumber);
    this.notice = this.i18n.t('orderCopied', { order: order.orderNumber });
    this.changeDetector.markForCheck();
  }

  private replace(value: PurchaseOrder): void {
    this.orders = this.orders.map(order => order.id === value.id ? {
      ...value,
      items: value.items.map(item => ({
        ...item,
        productName: order.items.find(current => current.productId === item.productId)?.productName ?? item.productName
      }))
    } : order);
    if (value.carrier) this.carrier[value.id] = value.carrier;
    if (value.trackingNumber) this.tracking[value.id] = value.trackingNumber;
  }
}
