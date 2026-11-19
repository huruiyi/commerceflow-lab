import { ChangeDetectorRef, Component, DestroyRef, Input, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin, Observable } from 'rxjs';
import { OrderLogistics, PurchaseOrder, WarehousePlan } from 'src/model/IPurchaseOrder';
import { ApiService, AfterSale, AfterSaleOffer } from '../services/api.service';

@Component({
  standalone: false,
  selector: 'app-line-after-sales',
  templateUrl: './line-after-sales.component.html',
})
export class LineAfterSalesComponent {
  @Input({ required: true }) order!: PurchaseOrder;
  @Input() merchant = false;
  private readonly api = inject(ApiService);
  private readonly changes = inject(ChangeDetectorRef);
  private readonly destroy = inject(DestroyRef);
  cases?: AfterSale[];
  offer?: AfterSaleOffer;
  lineId?: number;
  warehouseId?: number;
  outboundId?: string;
  plan?: WarehousePlan;
  quantity = 1;
  reason = '';
  busy = false;
  error = '';
  notes: Record<string, string> = {};
  tracking: Record<string, string> = {};
  carriers: Record<string, string> = {};
  parcels: Record<string, OrderLogistics | null | undefined> = {};
  returnCost: Record<string, number | null> = {};
  private requestId?: string;

  get canApply(): boolean {
    return (
      !this.merchant &&
      ['PAID', 'CONFIRMED', 'RECEIVED', 'COMPLETED'].includes(this.order.status) &&
      !this.cases?.some((value) => !['FAILED', 'REJECTED', 'COMPLETED'].includes(value.status))
    );
  }

  load(): void {
    this.run(
      forkJoin({
        cases: this.api.lineAfterSales(this.order.id, this.merchant),
        plan: this.api.warehousePlan(this.order.id, this.merchant),
      }),
      ({ cases, plan }) => {
        this.cases = cases;
        this.plan = plan;
      },
    );
  }

  get needsWarehouse(): boolean {
    return this.plan?.amounts.some((value) => value.warehouseId !== 1) ?? false;
  }

  get returning(): boolean {
    return ['RECEIVED', 'COMPLETED'].includes(this.order.status);
  }

  get warehouses(): number[] {
    return (this.plan?.amounts ?? [])
      .filter((value) => value.orderLineId === this.lineId)
      .map((value) => value.warehouseId);
  }

  get originalPackages(): WarehousePlan['shipments'] {
    return (this.plan?.shipments ?? []).filter(
      (value) => value.orderLineId === this.lineId && (!this.warehouseId || value.warehouseId === this.warehouseId),
    );
  }

  selectSource(): void {
    this.outboundId = this.originalPackages.length === 1 ? this.originalPackages[0].packageId : undefined;
    this.invalidate();
  }

  invalidate(): void {
    this.offer = undefined;
    this.requestId = undefined;
  }

  quote(): void {
    if (!this.lineId || this.quantity < 1) return;
    this.run(
      this.api.quoteAfterSale(this.order.id, this.lineId, this.quantity, this.warehouseId, this.outboundId),
      (offer) => {
        this.offer = offer;
        this.requestId = crypto.randomUUID();
      },
    );
  }

  submit(): void {
    if (!this.lineId || !this.requestId || !this.offer || !this.reason.trim()) return;
    this.run(
      this.api.submitAfterSale(this.order.id, {
        orderLineId: this.lineId,
        quantity: this.quantity,
        reason: this.reason.trim(),
        requestId: this.requestId,
        warehouseId: this.warehouseId,
        outboundId: this.outboundId,
      }),
      (value) => {
        this.update(value);
        this.invalidate();
        this.reason = '';
      },
    );
  }

  sendReturn(value: AfterSale): void {
    const trackingNumber = this.tracking[value.id]?.trim();
    const carrier = this.carriers[value.id]?.trim();
    if (!trackingNumber || !carrier) return;
    this.run(
      this.api.sendAfterSaleReturn(value.id, carrier, trackingNumber, this.returnCost[value.id] ?? null),
      (updated) => this.update(updated),
    );
  }

  loadReturnTracking(value: AfterSale): void {
    this.run(this.api.afterSaleReturnTracking(value.id, this.merchant), (parcel) => {
      this.parcels[value.id] = parcel;
    });
  }

  decide(value: AfterSale, accepted: boolean, inspection: boolean): void {
    const note = this.notes[value.id]?.trim();
    if (!note) return;
    this.run(this.api.decideAfterSale(value.id, inspection, { accepted, note }), (updated) => this.update(updated));
  }

  private update(value: AfterSale): void {
    this.cases = [value, ...(this.cases ?? []).filter((old) => old.id !== value.id)];
  }

  private run<T>(request: Observable<T>, accept: (value: T) => void): void {
    if (this.busy) return;
    this.busy = true;
    this.error = '';
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => {
          this.busy = false;
          this.changes.markForCheck();
        }),
      )
      .subscribe({
        next: accept,
        error: (error) => {
          this.error = error.error?.message ?? '';
          if (!this.error) this.error = 'REQUEST_FAILED';
        },
      });
  }
}
