import { statementDifferenceLabels, statementWarehouseStatuses } from './statement-reconciliation-labels';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { ReviewTarget, StatementDifferenceReviewsComponent } from './statement-difference-reviews.component';

interface Difference {
  kind: string;
  reference: string;
  expected: number | null;
  observed: number | null;
}

interface Dispatch {
  outboundId: string;
  warehouseId: number;
  status: string;
  planned: { orderLineId: number; productId: number; quantity: number }[];
  actual: { orderLineId: number; quantity: number }[] | null;
  decidedAt: string | null;
}

interface FulfillmentCheck {
  inventoryAvailable: boolean;
  warehouseAvailable: boolean;
  inventory: {
    outboundId: string;
    warehouseId: number;
    orderLineId: number;
    productId: number;
    quantity: number;
    postedAt: string;
  }[];
  dispatches: Dispatch[];
  returns: {
    receipt: {
      returnId: string;
      outboundId: string;
      warehouseId: number;
      orderLineId: number;
      expectedQuantity: number;
      receivedQuantity: number | null;
      status: string;
      receivedAt: string | null;
      inspectedAt: string | null;
      supplementId: string | null;
      qualifiedQuantity: number | null;
    };
    refundNumber: string | null;
    refundedQuantity: number | null;
  }[];
  differences: {
    kind: string;
    outboundId: string;
    orderLineId: number;
    expected: number | null;
    observed: number | null;
  }[];
}

interface Ticket {
  invoiceNumber: string;
  originalInvoiceNumber: string | null;
  reference: string;
  amount: number;
  currency: string;
}

interface OrderCheck {
  orderId: number;
  orderAmount: number | null;
  statementPayable: number;
  paidAmount: number | null;
  refundedAmount: number | null;
  paymentNumber: string | null;
  blueInvoiceNumber: string | null;
  redInvoiceNumbers: string[];
  netInvoice: number | null;
  refundNumbers: string[];
  entryReferences: string[];
  differences: Difference[];
  paidAt?: string | null;
  refundDetails?: { refundNumber: string; amount: number; currency: string; succeededAt: string }[];
  invoiceDetails?: { issue: Ticket; reds: Ticket[] } | null;
  fulfillment?: FulfillmentCheck | null;
}

interface Report {
  statement: { payable: number; startsAt: string; cutoffAt: string };
  entryPayable: number;
  paidOut: number;
  payouts: { id: string; channelPayoutNo: string | null; status: string; amount: number; resolvedAt?: string | null }[];
  differences: Difference[];
  orders: OrderCheck[];
  total: number;
  page: number;
  size: number;
  observedAt: string;
}

@Component({
  selector: 'app-statement-reconciliation',
  imports: [CommonModule, ListPaginationComponent, StatementDifferenceReviewsComponent],
  templateUrl: './statement-reconciliation.component.html',
})
export class StatementReconciliationComponent implements OnInit {
  readonly path = input.required<string>();
  readonly allowReview = input(false);
  readonly focusedReview = input<string | null>(null);
  readonly report = signal<Report | null>(null);
  readonly reviewTargets = computed<ReviewTarget[]>(() => {
    const report = this.report();
    if (!report) return [];
    return [
      ...report.differences.map((difference) => ({ ...difference, orderId: null, orderLineId: null })),
      ...report.orders.flatMap((order) => [
        ...order.differences.map((difference) => ({ ...difference, orderId: order.orderId, orderLineId: null })),
        ...(order.fulfillment?.differences ?? []).map(({ outboundId, ...difference }) => ({
          ...difference,
          orderId: order.orderId,
          reference: outboundId,
        })),
      ]),
    ];
  });
  readonly totalPages = computed(() => {
    const value = this.report();
    return value ? Math.ceil(value.total / value.size) : 0;
  });
  readonly loading = signal(false);
  readonly failed = signal(false);
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private requestedPage = 0;

  readonly labels = statementDifferenceLabels;

  readonly warehouseStatuses = statementWarehouseStatuses;

  actualQuantity(dispatch: Dispatch, orderLineId: number): number | null {
    return dispatch.actual?.find((line) => line.orderLineId === orderLineId)?.quantity ?? null;
  }

  ngOnInit(): void {
    this.load(0);
  }

  load(page = this.requestedPage): void {
    if (this.loading()) return;
    this.requestedPage = page;
    this.loading.set(true);
    this.failed.set(false);
    this.http
      .get<Report>(this.path(), { params: { page } })
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (value) => this.report.set(value),
        error: () => this.failed.set(true),
      });
  }
}
