import { DatePipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize } from 'rxjs';
import { AdminListPage } from 'src/model/IAdminListPage';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { statementDifferenceLabels, statementWarehouseStatuses } from '../stores/statement-reconciliation-labels';

type WorkKind = 'STATEMENT_DIFFERENCE' | 'RETURN_WORK';
type WorkPriority = 'HIGH' | 'NORMAL';
type WorkState = 'OPEN' | 'WAITING_EVIDENCE' | 'CLOSED';

interface WorkItem {
  kind: WorkKind;
  sourceId: string;
  merchantId: number;
  statementId: string | null;
  orderId: number | null;
  reasonCode: string;
  priority: WorkPriority;
  state: WorkState;
  updatedAt: string;
}

@Component({
  imports: [DatePipe, FormsModule, ListPaginationComponent, RouterLink],
  templateUrl: './exception-work.component.html',
})
export class ExceptionWorkComponent {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  readonly admin = inject(ActivatedRoute).snapshot.data['admin'] === true;
  readonly result = signal<AdminListPage<WorkItem> | null>(null);
  readonly busy = signal(false);
  readonly failed = signal(false);
  readonly pages = computed(() => {
    const result = this.result();
    return result ? Math.ceil(result.total / result.size) : 0;
  });
  readonly kinds: Record<WorkKind, string> = {
    STATEMENT_DIFFERENCE: '账单差异 / Statement discrepancy',
    RETURN_WORK: '售后异常 / Return exception',
  };
  readonly priorities: Record<WorkPriority, string> = { HIGH: '高 / High', NORMAL: '普通 / Normal' };
  readonly states: Record<WorkState, string> = {
    OPEN: '待处理 / Open',
    WAITING_EVIDENCE: '待凭证 / Awaiting evidence',
    CLOSED: '已结案 / Closed',
  };

  kind: WorkKind | '' = '';
  priority: WorkPriority | '' = '';
  state: WorkState | '' = '';
  merchantId: number | null = null;
  orderId: number | null = null;
  private criteria = new HttpParams();

  constructor() {
    this.load(0);
  }

  apply(): void {
    this.criteria = new HttpParams();
    const filters = { kind: this.kind, priority: this.priority, state: this.state, orderId: this.orderId };
    for (const [field, value] of Object.entries(filters)) {
      if (value) this.criteria = this.criteria.set(field, value);
    }
    if (this.admin && this.merchantId) this.criteria = this.criteria.set('merchantId', this.merchantId);
    this.load(0);
  }

  load(page = this.result()?.page ?? 0): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.failed.set(false);
    const role = this.admin ? 'admin' : 'merchant';
    this.http
      .get<AdminListPage<WorkItem>>(`/api/${role}/exception-work`, { params: this.criteria.set('page', page) })
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: (value) => this.result.set(value),
        error: () => {
          this.result.set(null);
          this.failed.set(true);
        },
      });
  }

  reason(item: WorkItem): string {
    const labels = item.kind === 'STATEMENT_DIFFERENCE' ? statementDifferenceLabels : statementWarehouseStatuses;
    return labels[item.reasonCode] ?? item.reasonCode;
  }

  source(item: WorkItem): (string | number)[] {
    const path = item.kind === 'RETURN_WORK' ? 'return-work-orders' : 'settlements';
    return this.admin ? ['/admin/stores', item.merchantId, path] : ['/merchant', path];
  }

  sourceParams(item: WorkItem): Record<string, string | null> {
    if (item.kind === 'RETURN_WORK') return { caseId: item.sourceId };
    return this.admin ? { statement: item.statementId, review: item.sourceId } : { statement: item.statementId };
  }
}
