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

  readonly labels: Record<string, string> = {
    STATEMENT_MERCHANDISE: '账单商品金额 / Statement merchandise',
    STATEMENT_FREIGHT: '账单运费 / Statement freight',
    STATEMENT_COMMISSION: '账单佣金 / Statement commission',
    STATEMENT_PAYABLE: '账单应结 / Statement payable',
    SETTLEMENT_ACCOUNT_MISSING: '结算账户查不到 / Settlement account missing',
    PAYOUT_AMOUNT: '打款金额 / Payout amount',
    PAYOUT_FAILED: '打款失败 / Payout failed',
    PAYOUT_CURRENCY: '打款币种 / Payout currency',
    MULTIPLE_PAYOUTS: '多次成功打款 / Multiple payouts',
    ORDER_MISSING: '订单查不到 / Order missing',
    ORDER_SCOPE: '订单行不符合单店结算范围 / Order ownership',
    PAYMENT_AMOUNT: '实付与订单金额 / Payment amount',
    PAYMENT_FACTS: '支付笔数或币种 / Payment facts',
    PAYMENT_REFUND_TOTAL: '支付退款总额 / Payment refund total',
    MERCHANDISE_ENTRY: '商品应结明细 / Merchandise entry',
    FREIGHT_ENTRY: '运费应结明细 / Freight entry',
    COMMISSION_ENTRY: '佣金明细 / Commission entry',
    REFUND_FACTS: '退款关联或币种 / Refund facts',
    REFUND_REVERSAL: '退款冲正金额 / Refund reversal',
    REFUND_ALLOCATION: '商品与运费退款分摊 / Refund allocation',
    BLUE_REFUND_REFERENCE: '正票扣减的原退款 / Refund included at issue',
    BLUE_AMOUNT: '正票金额 / Issued invoice amount',
    BLUE_FACTS: '正票支付引用或币种 / Issued invoice facts',
    RED_FACTS: '红票引用或币种 / Red invoice facts',
    RED_AMOUNT: '红票金额 / Red invoice amount',
    RED_NOT_CONFIRMED: '红冲尚未确认 / Red invoice unconfirmed',
    INVENTORY_OUTBOUND_QUANTITY: 'WMS 实发与库存出库数量差异 / Dispatched and posted quantity differ',
    WMS_OUTBOUND_MISSING: '库存出库缺少对应实发凭证 / Dispatch evidence missing',
  };

  readonly warehouseStatuses: Record<string, string> = {
    PENDING: '待实发 / Awaiting dispatch',
    DISPATCHED: '已实发 / Dispatched',
    VOIDED: '已拒绝出库 / Dispatch rejected',
    WAITING_RECEIPT: '待实收 / Awaiting receipt',
    RECEIVED: '待质检 / Awaiting inspection',
    PASSED: '质检合格 / Inspection passed',
    FAILED: '质检不通过 / Inspection failed',
    SHORT_RECEIVED: '短收 / Short receipt',
    REJECTED: '拒收 / Receipt rejected',
  };

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
