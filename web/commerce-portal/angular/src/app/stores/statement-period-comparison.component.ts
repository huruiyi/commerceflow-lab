import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';

interface Period {
  statement: {
    id: string;
    startsAt: string;
    cutoffAt: string;
    merchandise: number;
    freight: number;
    commission: number;
    payable: number;
  };
  paidOut: number;
  payouts: { id: string; channelPayoutNo: string | null; amount: number }[];
  refunds: { count: number; amount: number; commissionReversed: number; priorCount: number; priorAmount: number };
}

interface RefundOrigin {
  sourceKey: string;
  orderId: number;
  orderLineId: number | null;
  occurredAt: string;
  amount: number;
  commissionReversed: number;
  saleSourceKey: string | null;
  originStatementId: string | null;
  originCutoffAt: string | null;
}

interface Report {
  previous: Period | null;
  current: Period;
  refunds: RefundOrigin[];
  total: number;
  page: number;
  size: number;
}

@Component({
  selector: 'app-statement-period-comparison',
  imports: [CurrencyPipe, DatePipe, ListPaginationComponent],
  templateUrl: './statement-period-comparison.component.html',
})
export class StatementPeriodComparisonComponent implements OnInit {
  readonly path = input.required<string>();
  readonly report = signal<Report | null>(null);
  readonly periods = computed(() => {
    const value = this.report();
    if (!value) return [];
    const current = { label: '本期 / Current period', period: value.current };
    return value.previous ? [{ label: '上期 / Previous period', period: value.previous }, current] : [current];
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

  ngOnInit(): void {
    this.load();
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
