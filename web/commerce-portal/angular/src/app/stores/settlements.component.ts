import { AdminListPage } from 'src/model/IAdminListPage';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { forkJoin, Observable } from 'rxjs';
import { StatementPeriodComparisonComponent } from './statement-period-comparison.component';
import { StatementReconciliationComponent } from './statement-reconciliation.component';

interface Amounts {
  merchandise: number;
  freight: number;
  commission: number;
}

interface Statement extends Amounts {
  id: string;
  startsAt: string;
  cutoffAt: string;
  payable: number;
}

interface Entry extends Amounts {
  sourceKey: string;
  orderId: number;
  orderLineId?: number;
  kind: string;
  occurredAt: string;
}

interface Payout {
  id: string;
  status: string;
  channelPayoutNo?: string;
  amount: number;
  resolvedAt?: string;
  requestedBy: number;
  createdAt: string;
  recoveryOf?: string;
  recoveryReason?: string;
}

@Component({
  selector: 'app-merchant-settlements',
  imports: [
    CommonModule,
    FormsModule,
    StatementReconciliationComponent,
    StatementPeriodComparisonComponent,
    ListPaginationComponent,
  ],
  templateUrl: './settlements.component.html',
})
export class SettlementsComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly admin = this.route.snapshot.data['admin'] === true;
  readonly merchantId = Number(this.route.snapshot.paramMap.get('id'));
  readonly statements = signal<AdminListPage<Statement> | null>(null);
  readonly entries = signal<AdminListPage<Entry> | null>(null);
  readonly pending = signal<AdminListPage<Entry> | null>(null);
  readonly selected = signal<string | undefined>(undefined);
  readonly reconciliationPath = signal<string | undefined>(undefined);
  readonly focusedReview = signal<string | null>(null);
  readonly periodComparisonPath = signal<string | undefined>(undefined);
  readonly payouts = signal<Payout[]>([]);
  readonly busy = signal(false);
  readonly message = signal('');
  commissionBps = 500;
  payoutReason = '';
  private closeRequest?: string;
  private payoutRequest?: { fingerprint: string; id: string };

  constructor() {
    this.reload('', false, () => {
      const statement = this.route.snapshot.queryParamMap.get('statement');
      const review = this.admin ? this.route.snapshot.queryParamMap.get('review') : null;
      if (statement) this.detail(statement, true, review);
    });
  }

  enable(): void {
    this.run(this.http.put(`${this.adminBase}/settlement`, { commissionBps: this.commissionBps }), () =>
      this.message.set('结算账户已启用 / Settlement account enabled'),
    );
  }

  close(): void {
    const requestId = (this.closeRequest ??= crypto.randomUUID());
    this.run(this.http.post<Statement | null>(this.statementPath, { requestId }), (value) => {
      this.closeRequest = undefined;
      if (value) {
        this.reload('账单已冻结 / Statement frozen', true);
      } else {
        this.message.set('当前无正应结金额 / No positive balance');
      }
    });
  }

  detail(id: string, showReconciliation = false, review: string | null = null): void {
    const path = `${this.statementPath}/${encodeURIComponent(id)}`;
    this.run(
      forkJoin({
        entries: this.readPage<Entry>(path + '/entries', 0),
        payouts: this.http.get<Payout[]>(path + '/payouts'),
      }),
      (details) => {
        this.reconciliationPath.set(undefined);
        this.periodComparisonPath.set(undefined);
        this.payoutReason = '';
        this.selected.set(id);
        this.focusedReview.set(review);
        this.entries.set(details.entries);
        this.payouts.set(details.payouts);
        if (showReconciliation) this.reconcile();
      },
    );
  }

  reconcile(): void {
    const id = this.selected();
    if (id) this.reconciliationPath.set(`${this.statementPath}/${encodeURIComponent(id)}/reconciliation`);
  }

  comparePeriods(): void {
    const id = this.selected();
    if (id) this.periodComparisonPath.set(`${this.statementPath}/${id}/period-comparison`);
  }

  requestPayout(): void {
    const statement = this.selected();
    if (!statement || !this.admin) return;
    const latest = this.payouts()[0];
    const recoveryOf = latest?.status === 'FAILED' ? latest.id : undefined;
    const reason = recoveryOf ? this.payoutReason.trim() : undefined;
    if (latest && (!recoveryOf || !reason || !latest.channelPayoutNo)) return;
    const fingerprint = JSON.stringify({ statement, recoveryOf, reason });
    if (this.payoutRequest?.fingerprint !== fingerprint) {
      this.payoutRequest = { fingerprint, id: crypto.randomUUID() };
    }
    this.run(
      this.http.post<Payout>(`${this.statementPath}/${statement}/payouts`, {
        requestId: this.payoutRequest.id,
        recoveryOf,
        reason,
      }),
      (value) => {
        this.payoutRequest = undefined;
        this.payoutReason = '';
        this.payouts.update((items) => [value, ...items.filter((item) => item.id !== value.id)]);
        this.message.set('打款请求已记录 / Payout request recorded');
      },
    );
  }

  reload(notice = '', reset = false, loaded?: () => void): void {
    this.run(
      forkJoin({
        statements: this.readPage<Statement>(this.statementPath, reset ? 0 : (this.statements()?.page ?? 0)),
        pending: this.readPage<Entry>(this.pendingPath, reset ? 0 : (this.pending()?.page ?? 0)),
      }),
      (value) => {
        this.statements.set(value.statements);
        this.pending.set(value.pending);
        this.message.set(notice);
        loaded?.();
      },
    );
  }

  loadStatements(page: number): void {
    this.run(this.readPage<Statement>(this.statementPath, page), (value) => this.statements.set(value));
  }

  loadPending(page: number): void {
    this.run(this.readPage<Entry>(this.pendingPath, page), (value) => this.pending.set(value));
  }

  loadEntries(page: number): void {
    const id = this.selected();
    if (id) {
      this.run(this.readPage<Entry>(`${this.statementPath}/${id}/entries`, page), (value) => this.entries.set(value));
    }
  }

  pageCount(page: AdminListPage<unknown>): number {
    return Math.ceil(page.total / page.size);
  }

  private readPage<T>(path: string, page: number): Observable<AdminListPage<T>> {
    return this.http.get<AdminListPage<T>>(path + '/page', { params: { page } });
  }

  private get pendingPath(): string {
    return this.admin ? `${this.adminBase}/settlement/pending` : '/api/merchant/settlement/pending';
  }

  private get adminBase(): string {
    return `/api/admin/stores/${this.merchantId}`;
  }

  private get statementPath(): string {
    return this.admin ? `${this.adminBase}/statements` : '/api/merchant/settlement/statements';
  }

  private run<T>(request: Observable<T>, next: (value: T) => void): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.message.set('');
    request.subscribe({
      next: (value) => {
        this.busy.set(false);
        next(value);
      },
      error: (error) => {
        this.busy.set(false);
        this.message.set(error.error?.message || '操作失败 / Request failed');
      },
    });
  }
}
