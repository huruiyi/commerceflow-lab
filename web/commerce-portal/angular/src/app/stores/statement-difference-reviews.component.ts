import { DatePipe } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, input, OnInit, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { finalize, map } from 'rxjs';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { StatementDifferenceDispositionComponent } from './statement-difference-disposition.component';

export interface ReviewTarget {
  orderId: number | null;
  orderLineId: number | null;
  kind: string;
  reference: string;
  expected: number | null;
  observed: number | null;
}

type Decision = 'CONFIRMED' | 'NO_ACTION';

interface Review {
  requestId: string;
  orderId: number | null;
  orderLineId: number | null;
  differenceKind: string;
  evidenceReference: string;
  expectedValue: number | null;
  observedValue: number | null;
  decision: Decision;
  reason: string;
  reviewedBy: number;
  reviewedAt: string;
}

interface History {
  rows: Review[];
  total: number;
  page: number;
  size: number;
}

@Component({
  selector: 'app-statement-difference-reviews',
  imports: [DatePipe, FormsModule, ListPaginationComponent, StatementDifferenceDispositionComponent],
  templateUrl: './statement-difference-reviews.component.html',
})
export class StatementDifferenceReviewsComponent implements OnInit {
  readonly path = input.required<string>();
  readonly targets = input.required<ReviewTarget[]>();
  readonly labels = input.required<Record<string, string>>();
  readonly reportPage = input.required<number>();
  readonly reportSize = input.required<number>();
  readonly reportBusy = input(false);
  readonly focusedReview = input<string | null>(null);
  readonly reloadReport = output<void>();

  readonly history = signal<History | null>(null);
  readonly totalPages = computed(() => {
    const value = this.history();
    return value ? Math.ceil(value.total / value.size) : 0;
  });
  readonly loading = signal(false);
  readonly historyError = signal(false);
  readonly saving = signal(false);
  readonly saved = signal(false);
  readonly saveError = signal('');
  readonly activeReview = signal('');
  readonly filterReview = signal<string | null>(null);
  readonly decisions: Record<Decision, string> = {
    CONFIRMED: '确认差异 / Confirm discrepancy',
    NO_ACTION: '无需处置 / No action',
  };

  selected: ReviewTarget | null = null;
  decision: Decision = 'CONFIRMED';
  reason = '';
  private draft?: { payload: string; requestId: string };
  private historyPage = 0;
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);

  ngOnInit(): void {
    this.filterReview.set(this.focusedReview());
    this.loadHistory();
  }

  showAllReviews(): void {
    this.filterReview.set(null);
    this.loadHistory(0);
  }

  loadHistory(page = this.historyPage): void {
    this.historyPage = page;
    this.historyError.set(false);
    this.loading.set(true);
    const id = this.filterReview();
    const request = id
      ? this.http
          .get<Review>(this.path() + '/' + encodeURIComponent(id))
          .pipe(map((review): History => ({ rows: [review], total: 1, page: 0, size: 1 })))
      : this.http.get<History>(this.path(), { params: { page } });
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (value) => {
          this.history.set(value);
          if (id) this.activeReview.set(id);
        },
        error: () => this.historyError.set(true),
      });
  }

  save(): void {
    const target = this.selected;
    const reason = this.reason.trim();
    if (this.saving() || this.loading() || this.reportBusy() || !target || !this.targets().includes(target) || !reason)
      return;
    const body = { page: this.reportPage(), size: this.reportSize(), target, decision: this.decision, reason };
    const payload = JSON.stringify(body);
    if (this.draft?.payload !== payload) this.draft = { payload, requestId: crypto.randomUUID() };
    this.saving.set(true);
    this.saved.set(false);
    this.saveError.set('');
    this.http
      .post<Review>(this.path(), { ...body, requestId: this.draft.requestId })
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: () => {
          this.draft = undefined;
          this.selected = null;
          this.reason = '';
          this.saved.set(true);
          this.showAllReviews();
        },
        error: (error: HttpErrorResponse) => {
          if (error.status === 409) {
            this.selected = null;
            this.reloadReport.emit();
            this.saveError.set(
              '复核未登记，请根据最新对账结果重新选择差异 / Review was rejected. Select current evidence.',
            );
          } else {
            this.saveError.set('暂时无法登记复核，请重试 / Review is unavailable. Retry.');
          }
        },
      });
  }
}
