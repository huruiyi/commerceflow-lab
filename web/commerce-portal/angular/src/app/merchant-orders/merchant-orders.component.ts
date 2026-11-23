import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { catchError, EMPTY, exhaustMap, filter, finalize, forkJoin, Observable, of } from 'rxjs';
import { Subscription, switchMap, take, timeout, timer } from 'rxjs';
import { initialOrderSearch, OrderSearchCriteria, OrderSearchPage, PurchaseOrder } from 'src/model/IPurchaseOrder';
import { WarehousePlan } from 'src/model/IPurchaseOrder';
import { orderSearchParams, readOrderSearch } from '../order-list-filter/order-search-query';
import { AccountService } from '../services/account.service';
import { ApiService, FulfillmentIntent, FulfillmentOperation, FulfillmentReceipt } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

type FulfillmentOrder = Pick<PurchaseOrder, 'id' | 'orderNumber'>;

interface StoredFulfillment {
  order: FulfillmentOrder;
  intent: FulfillmentIntent;
}

interface FulfillmentRecovery extends StoredFulfillment {
  message: 'fulfillmentResultUnknown' | 'fulfillmentResultMissing' | 'fulfillmentResultPending' | 'orderStatusPending';
}

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
  plans: Record<number, WarehousePlan | undefined> = {};
  warehousePlanFailed = new Set<number>();
  private warehouseRequests = new Map<number, Subscription>();
  shipmentProgress: Record<number, { status: string; lastError?: string } | undefined> = {};
  recoveries = new Map<number, FulfillmentRecovery>();
  expandedOrders = new Set<number>();
  notice?: { type: 'success' | 'danger' | 'info'; text: string };
  pending = new Set<number>();

  constructor(
    private api: ApiService,
    private account: AccountService,
    private i18n: I18nService,
    private changeDetector: ChangeDetectorRef,
    private destroyRef: DestroyRef,
    private route: ActivatedRoute,
    private router: Router,
  ) {}

  ngOnInit(): void {
    this.restoreFulfillment();
    this.route.queryParamMap
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((params) => this.load(readOrderSearch(params)));
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.load());
  }

  applyCriteria(criteria: OrderSearchCriteria): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: orderSearchParams(criteria) });
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
    this.beginFulfillment(order, { operation: 'CONFIRM' });
  }

  saveShipment(order: PurchaseOrder): void {
    if (order.status !== 'SHIPPED' || this.shipmentInvalid(order) || this.shipmentUnchanged(order)) return;
    this.beginFulfillment(order, {
      operation: 'UPDATE_SHIPMENT',
      carrier: this.carrier[order.id].trim(),
      trackingNumber: this.tracking[order.id].trim(),
      expectedShipmentVersion: order.shipmentVersion ?? 0,
    });
  }

  savePackage(order: PurchaseOrder): void {
    if (
      this.shipmentInvalid(order) ||
      this.pending.has(order.id) ||
      this.shipmentProgress[order.id]?.status === 'PENDING' ||
      this.packageWarehouseUnavailable(order.id)
    )
      return;
    const lines = order.items
      .map((item) => ({ orderLineId: item.id, quantity: this.packageQuantities[item.id] ?? 0 }))
      .filter((line) => line.quantity > 0);
    if (!lines.length || lines.some((line) => !Number.isInteger(line.quantity))) return;
    this.beginFulfillment(order, {
      operation: 'SHIP',
      carrier: this.carrier[order.id].trim(),
      trackingNumber: this.tracking[order.id].trim(),
      warehouseId: this.warehouses[order.id],
      lines,
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
    if (!this.recoveries.has(order.id)) {
      if (order.carrier) this.carrier[order.id] = order.carrier;
      if (order.trackingNumber) this.tracking[order.id] = order.trackingNumber;
    }
    this.expandedOrders.add(order.id);
    this.loadWarehousePlan(order.id);
  }

  warehouseOptions(orderId: number): number[] {
    return [...new Set((this.plans[orderId]?.amounts ?? []).map((value) => value.warehouseId))];
  }

  isMultiWarehouse(orderId: number): boolean {
    return this.warehouseOptions(orderId).some((warehouse) => warehouse !== 1);
  }

  packageWarehouseUnavailable(orderId: number): boolean {
    if (!this.plans[orderId]) return true;
    const options = this.warehouseOptions(orderId);
    const selected = this.warehouses[orderId];
    return options.some((warehouse) => warehouse !== 1) && (selected === undefined || !options.includes(selected));
  }

  refreshShipment(orderId: number): void {
    this.load();
    this.loadWarehousePlan(orderId);
  }

  loadWarehousePlan(orderId: number): void {
    this.warehouseRequests.get(orderId)?.unsubscribe();
    const requests = new Subscription();
    this.warehouseRequests.set(orderId, requests);
    delete this.plans[orderId];
    this.warehousePlanFailed.delete(orderId);
    requests.add(
      this.api
        .merchantShipmentStatus(orderId)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (progress) => {
            this.shipmentProgress[orderId] = progress;
            this.changeDetector.markForCheck();
          },
          error: (error) => {
            if (error.status === 404) delete this.shipmentProgress[orderId];
            this.changeDetector.markForCheck();
          },
        }),
    );
    requests.add(
      this.api
        .warehousePlan(orderId, true)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (plan) => {
            this.plans[orderId] = plan;
            const selected = this.warehouses[orderId];
            if (selected !== undefined && !this.warehouseOptions(orderId).includes(selected)) {
              delete this.warehouses[orderId];
            }
            this.changeDetector.markForCheck();
          },
          error: () => {
            this.warehousePlanFailed.add(orderId);
            this.changeDetector.markForCheck();
          },
        }),
    );
  }

  async copyOrderNumber(order: PurchaseOrder): Promise<void> {
    await navigator.clipboard.writeText(order.orderNumber);
    this.notice = { type: 'success', text: this.i18n.t('orderCopied', { order: order.orderNumber }) };
    this.changeDetector.markForCheck();
  }

  get ordersOutsideResults(): FulfillmentOrder[] {
    const visible = new Set(this.orders.map((order) => order.id));
    return [...this.recoveries.values()].map(({ order }) => order).filter((order) => !visible.has(order.id));
  }

  openOriginalOrder(order: FulfillmentOrder): void {
    this.applyCriteria({ ...initialOrderSearch, q: order.orderNumber });
  }

  checkFulfillment(order: FulfillmentOrder): void {
    const recovery = this.recoveries.get(order.id);
    if (!recovery || this.pending.has(order.id)) return;
    const receipt = this.api.fulfillmentReceipt(recovery.intent.commandId).pipe(
      timeout(8000),
      catchError((error) => {
        if (error instanceof HttpErrorResponse && error.status === 404) return of(null);
        throw error;
      }),
    );
    this.watchFulfillment(order, recovery, receipt, true);
  }

  retryFulfillment(order: FulfillmentOrder): void {
    const recovery = this.recoveries.get(order.id);
    if (recovery?.message === 'fulfillmentResultMissing' && !this.pending.has(order.id)) {
      this.submitFulfillment(order, recovery);
    }
  }

  private beginFulfillment(order: PurchaseOrder, operation: FulfillmentOperation): void {
    if (this.pending.has(order.id) || this.recoveries.has(order.id)) return;
    const intent = {
      ...operation,
      orderId: order.id,
      commandId: crypto.randomUUID(),
      occurredAt: new Date().toISOString(),
    };
    const stored = { order: { id: order.id, orderNumber: order.orderNumber }, intent };
    const recovery: FulfillmentRecovery = { ...stored, message: 'fulfillmentResultUnknown' };
    this.recoveries.set(order.id, recovery);
    this.persistFulfillment(order.id, stored);
    this.submitFulfillment(order, recovery);
  }

  private submitFulfillment(order: FulfillmentOrder, recovery: FulfillmentRecovery): void {
    recovery.message = 'fulfillmentResultUnknown';
    const receipt = this.api.submitFulfillment(recovery.intent).pipe(
      timeout(8000),
      catchError((error) => {
        if (error instanceof HttpErrorResponse && [400, 401, 403, 404, 409, 422].includes(error.status)) {
          this.clearFulfillment(order.id);
          this.notice = { type: 'danger', text: error.error?.message ?? this.i18n.t('requestFailed') };
          return EMPTY;
        }
        throw error;
      }),
    );
    this.watchFulfillment(order, recovery, receipt, false);
  }

  private watchFulfillment(
    order: FulfillmentOrder,
    recovery: FulfillmentRecovery,
    request: Observable<FulfillmentReceipt | null>,
    lookup: boolean,
  ): void {
    this.pending.add(order.id);
    this.notice = undefined;
    request
      .pipe(
        switchMap((receipt) => {
          if (receipt?.status !== 'PENDING') return of(receipt);
          recovery.message = 'fulfillmentResultPending';
          this.changeDetector.markForCheck();
          return timer(1000, 1000).pipe(
            exhaustMap(() => this.api.fulfillmentReceipt(recovery.intent.commandId)),
            filter((response) => response.status !== 'PENDING'),
            take(1),
            timeout({ first: 60000 }),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.pending.delete(order.id);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (receipt) => {
          if (!receipt) {
            recovery.message = 'fulfillmentResultMissing';
            return;
          }
          if (receipt.status === 'REVIEW_REQUIRED') {
            recovery.message = 'orderStatusPending';
            return;
          }
          this.clearFulfillment(order.id);
          if (receipt.status === 'REJECTED') {
            this.notice = { type: 'danger', text: receipt.errorCode || this.i18n.t('requestFailed') };
            if (recovery.intent.operation === 'SHIP') this.refreshShipment(order.id);
            return;
          }
          const operation = recovery.intent.operation;
          const successKey =
            operation === 'CONFIRM' ? 'orderConfirmed' : operation === 'SHIP' ? 'orderShipped' : 'shipmentUpdated';
          this.notice = {
            type: 'success',
            text: this.i18n.t(lookup ? 'fulfillmentResultRecovered' : successKey, { order: order.orderNumber }),
          };
          if (recovery.intent.operation === 'SHIP') {
            this.tracking[order.id] = '';
            recovery.intent.lines.forEach((line) => delete this.packageQuantities[line.orderLineId]);
            this.refreshShipment(order.id);
          } else {
            this.load();
          }
        },
        error: () => {
          if (recovery.message !== 'fulfillmentResultPending') recovery.message = 'fulfillmentResultUnknown';
        },
      });
  }

  private restoreFulfillment(): void {
    const prefix = this.fulfillmentStoragePrefix();
    if (!prefix) return;
    let keys: string[];
    try {
      keys = Object.keys(sessionStorage).filter((key) => key.startsWith(prefix));
    } catch {
      return;
    }
    for (const key of keys) {
      const stored = this.readFulfillment(key);
      if (!stored || key !== prefix + stored.order.id) continue;
      const { order, intent } = stored;
      this.recoveries.set(order.id, { ...stored, message: 'fulfillmentResultUnknown' });
      if (intent.operation !== 'CONFIRM') {
        this.carrier[order.id] = intent.carrier;
        this.tracking[order.id] = intent.trackingNumber;
        this.expandedOrders.add(order.id);
        if (intent.operation === 'SHIP') {
          this.warehouses[order.id] = intent.warehouseId;
          for (const line of intent.lines) this.packageQuantities[line.orderLineId] = line.quantity;
          this.loadWarehousePlan(order.id);
        }
      }
      this.checkFulfillment(order);
    }
  }

  private readFulfillment(key: string): StoredFulfillment | undefined {
    try {
      const stored = JSON.parse(sessionStorage.getItem(key) ?? 'null') as StoredFulfillment | null;
      if (!stored?.intent?.commandId || !stored.order?.orderNumber || stored.intent.orderId !== stored.order.id) return;
      if (stored.intent.operation === 'SHIP' && !Array.isArray(stored.intent.lines)) return;
      return stored;
    } catch {
      return;
    }
  }

  private clearFulfillment(orderId: number): void {
    this.recoveries.delete(orderId);
    this.persistFulfillment(orderId);
  }

  private persistFulfillment(orderId: number, stored?: StoredFulfillment): void {
    const prefix = this.fulfillmentStoragePrefix();
    if (!prefix) return;
    const key = prefix + orderId;
    try {
      if (stored) sessionStorage.setItem(key, JSON.stringify(stored));
      else sessionStorage.removeItem(key);
    } catch {
      // Keep the current intent available while the page remains open.
    }
  }

  private fulfillmentStoragePrefix(): string | undefined {
    const actorId = this.account.userProfile()?.id;
    return actorId === undefined ? undefined : `commerceflow-fulfillment:${actorId}:`;
  }
}
