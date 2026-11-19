import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, finalize, forkJoin, Observable, of, TimeoutError } from 'rxjs';
import { initialOrderSearch, OrderSearchCriteria, OrderSearchPage, PurchaseOrder } from 'src/model/IPurchaseOrder';
import { WarehousePlan } from 'src/model/IPurchaseOrder';
import { ApiService } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-merchant-orders',
  templateUrl: './merchant-orders.component.html',
  styleUrls: ['./merchant-orders.component.css'],
})
export class MerchantOrdersComponent implements OnInit {
  orders: PurchaseOrder[] = [];
  criteria = { ...initialOrderSearch };
  page?: OrderSearchPage;
  loading = true;
  loadFailed = false;
  private loadVersion = 0;
  carrier: Record<number, string> = {};
  tracking: Record<number, string> = {};
  packageQuantities: Record<number, number | null> = {};
  warehouses: Record<number, number | undefined> = {};
  plans: Record<number, WarehousePlan> = {};
  private packageRequests = new Map<number, { key: string; id: string }>();
  expandedOrders = new Set<number>();
  notice?: { type: 'success' | 'danger' | 'info'; text: string };
  pending = new Set<number>();

  constructor(
    private api: ApiService,
    private i18n: I18nService,
    private changeDetector: ChangeDetectorRef,
    private destroyRef: DestroyRef,
  ) {}

  ngOnInit(): void {
    this.load();
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.load());
  }

  load(criteria: OrderSearchCriteria = this.criteria): void {
    this.criteria = criteria;
    this.loading = true;
    this.loadFailed = false;
    const version = ++this.loadVersion;
    forkJoin({
      page: this.api.searchOrders(criteria, true),
      products: this.api.allProducts().pipe(catchError(() => of([]))),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ page, products }) => {
          if (version !== this.loadVersion) return;
          this.page = page;
          this.criteria = { ...criteria, page: page.page };
          const names = new Map(products.map((product) => [product.id, product.name]));
          this.orders = page.orders.map((order) => ({
            ...order,
            items: order.items.map((item) => ({ ...item, productName: names.get(item.productId) ?? item.productName })),
          }));
          this.orders.forEach((order) => {
            if (order.carrier && !this.expandedOrders.has(order.id)) this.carrier[order.id] = order.carrier;
            if (order.trackingNumber && !this.expandedOrders.has(order.id))
              this.tracking[order.id] = order.trackingNumber;
          });
          this.loading = false;
          this.changeDetector.markForCheck();
        },
        error: () => {
          if (version !== this.loadVersion) return;
          this.page = undefined;
          this.orders = [];
          this.loading = false;
          this.loadFailed = true;
          this.changeDetector.markForCheck();
        },
      });
  }

  confirm(order: PurchaseOrder): void {
    this.execute(order.id, this.api.confirmMerchantOrder(order.id), (value) =>
      value.status === 'PAID' ? 'orderConfirmationAccepted' : 'orderConfirmed',
    );
  }

  saveShipment(order: PurchaseOrder): void {
    if (this.shipmentInvalid(order) || this.shipmentUnchanged(order)) return;
    const request =
      order.status === 'SHIPPED'
        ? this.api.updateMerchantShipment(
            order.id,
            this.carrier[order.id].trim(),
            this.tracking[order.id].trim(),
            order.shipmentVersion ?? 0,
          )
        : this.api.shipMerchantOrder(order.id, this.carrier[order.id].trim(), this.tracking[order.id].trim());
    this.execute(order.id, request, (value) => {
      if (order.status === 'SHIPPED' && order.shipmentVersion === value.shipmentVersion) {
        return 'shipmentUpdateAccepted';
      }
      if (value.status !== 'SHIPPED') return 'shipmentAccepted';
      return order.status === 'SHIPPED' ? 'shipmentUpdated' : 'orderShipped';
    });
  }

  savePackage(order: PurchaseOrder): void {
    if (this.shipmentInvalid(order) || this.pending.has(order.id)) return;
    const lines = order.items
      .map((item) => ({ orderLineId: item.id, quantity: this.packageQuantities[item.id] ?? 0 }))
      .filter((line) => line.quantity > 0);
    if (!lines.length || lines.some((line) => !Number.isInteger(line.quantity))) return;
    const carrier = this.carrier[order.id].trim();
    const trackingNumber = this.tracking[order.id].trim();
    const warehouseId = this.warehouses[order.id];
    if (this.warehouseOptions(order.id).some((value) => value !== 1) && !warehouseId) return;
    const key = JSON.stringify({ carrier, trackingNumber, lines, warehouseId });
    const previous = this.packageRequests.get(order.id);
    const id = previous?.key === key ? previous.id : crypto.randomUUID();
    this.packageRequests.set(order.id, { key, id });
    this.pending.add(order.id);
    this.api
      .shipMerchantPackage(order.id, id, carrier, trackingNumber, lines, warehouseId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.pending.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: () => {
          this.tracking[order.id] = '';
          order.items.forEach((item) => delete this.packageQuantities[item.id]);
          this.notice = { type: 'success', text: this.i18n.t('packageShipped') };
          this.load();
          this.loadWarehousePlan(order.id);
        },
        error: (error) => {
          if (error instanceof Error && error.message === 'REJECTED') this.packageRequests.delete(order.id);
          this.notice = {
            type: error instanceof TimeoutError ? 'info' : 'danger',
            text: this.i18n.t(error instanceof TimeoutError ? 'orderStatusPending' : 'requestFailed'),
          };
        },
      });
  }

  shipmentInvalid(order: PurchaseOrder): boolean {
    return !(this.carrier[order.id] || '').trim() || !(this.tracking[order.id] || '').trim();
  }

  shipmentUnchanged(order: PurchaseOrder): boolean {
    return (
      order.status === 'SHIPPED' &&
      (this.carrier[order.id] || '').trim() === (order.carrier || '').trim() &&
      (this.tracking[order.id] || '').trim() === (order.trackingNumber || '').trim()
    );
  }

  toggleDetails(orderId: number): void {
    if (this.expandedOrders.has(orderId)) {
      this.expandedOrders.delete(orderId);
    } else {
      this.expandedOrders.add(orderId);
      this.loadWarehousePlan(orderId);
    }
  }

  prepareShipment(order: PurchaseOrder): void {
    if (order.carrier) this.carrier[order.id] = order.carrier;
    if (order.trackingNumber) this.tracking[order.id] = order.trackingNumber;
    this.expandedOrders.add(order.id);
    this.loadWarehousePlan(order.id);
  }

  warehouseOptions(orderId: number): number[] {
    return [...new Set((this.plans[orderId]?.amounts ?? []).map((value) => value.warehouseId))];
  }

  isMultiWarehouse(orderId: number): boolean {
    return this.warehouseOptions(orderId).some((warehouse) => warehouse !== 1);
  }

  private loadWarehousePlan(orderId: number): void {
    this.api
      .warehousePlan(orderId, true)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (plan) => {
          this.plans[orderId] = plan;
          this.changeDetector.markForCheck();
        },
        error: () => {
          this.notice = { type: 'danger', text: this.i18n.t('requestFailed') };
          this.changeDetector.markForCheck();
        },
      });
  }

  async copyOrderNumber(order: PurchaseOrder): Promise<void> {
    await navigator.clipboard.writeText(order.orderNumber);
    this.notice = { type: 'success', text: this.i18n.t('orderCopied', { order: order.orderNumber }) };
    this.changeDetector.markForCheck();
  }

  private execute(
    orderId: number,
    request: Observable<PurchaseOrder>,
    successKey: (order: PurchaseOrder) => string,
  ): void {
    if (this.pending.has(orderId)) return;
    this.pending.add(orderId);
    this.notice = undefined;
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.pending.delete(orderId);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (value) => {
          if (value.carrier) this.carrier[value.id] = value.carrier;
          if (value.trackingNumber) this.tracking[value.id] = value.trackingNumber;
          this.notice = { type: 'success', text: this.i18n.t(successKey(value), { order: value.orderNumber }) };
          this.load();
        },
        error: (error) => {
          this.notice =
            error instanceof TimeoutError
              ? { type: 'info', text: this.i18n.t('orderStatusPending') }
              : { type: 'danger', text: error.error?.message ?? this.i18n.t('requestFailed') };
        },
      });
  }
}
