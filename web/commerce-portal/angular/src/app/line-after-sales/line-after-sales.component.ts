import { ChangeDetectorRef, Component, DestroyRef, Input, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { PurchaseOrder } from 'src/model/IPurchaseOrder';
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
  quantity = 1;
  reason = '';
  busy = false;
  error = '';
  notes: Record<string, string> = {};
  tracking: Record<string, string> = {};
  returnCost: Record<string, number | null> = {};
  received: Record<string, number> = {};
  private requestId?: string;

  get canApply(): boolean {
    return (
      !this.merchant &&
      ['PAID', 'CONFIRMED', 'RECEIVED', 'COMPLETED'].includes(this.order.status) &&
      !this.cases?.some((value) => !['FAILED', 'REJECTED', 'COMPLETED'].includes(value.status))
    );
  }

  load(): void {
    this.run(this.api.lineAfterSales(this.order.id, this.merchant), (cases) => {
      this.cases = cases;
    });
  }

  invalidate(): void {
    this.offer = undefined;
    this.requestId = undefined;
  }

  quote(): void {
    if (!this.lineId || this.quantity < 1) return;
    this.run(this.api.quoteAfterSale(this.order.id, this.lineId, this.quantity), (offer) => {
      this.offer = offer;
      this.requestId = crypto.randomUUID();
    });
  }

  submit(): void {
    if (!this.lineId || !this.requestId || !this.offer || !this.reason.trim()) return;
    this.run(
      this.api.submitAfterSale(this.order.id, {
        orderLineId: this.lineId,
        quantity: this.quantity,
        reason: this.reason.trim(),
        requestId: this.requestId,
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
    if (!trackingNumber) return;
    this.run(this.api.sendAfterSaleReturn(value.id, trackingNumber, this.returnCost[value.id] ?? null), (updated) =>
      this.update(updated),
    );
  }

  decide(value: AfterSale, accepted: boolean, inspection: boolean): void {
    const note = this.notes[value.id]?.trim();
    if (!note) return;
    const receivedQuantity = inspection ? (this.received[value.id] ?? value.quantity) : undefined;
    this.run(this.api.decideAfterSale(value.id, inspection, { accepted, note, receivedQuantity }), (updated) =>
      this.update(updated),
    );
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
