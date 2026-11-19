import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { finalize, forkJoin, Observable } from 'rxjs';

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

@Component({
  selector: 'app-merchant-settlements',
  imports: [CommonModule, FormsModule],
  templateUrl: './settlements.component.html',
})
export class SettlementsComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly admin = this.route.snapshot.data['admin'] === true;
  readonly merchantId = Number(this.route.snapshot.paramMap.get('id'));
  readonly statements = signal<Statement[]>([]);
  readonly entries = signal<Entry[]>([]);
  readonly pending = signal<Entry[]>([]);
  readonly selected = signal<string | undefined>(undefined);
  readonly busy = signal(false);
  readonly message = signal('');
  commissionBps = 500;
  private closeRequest?: string;

  constructor() {
    this.reload();
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
      if (value) this.statements.update((items) => [value, ...items.filter((item) => item.id !== value.id)]);
      this.message.set(value ? '账单已冻结 / Statement frozen' : '当前无正应结金额 / No positive balance');
    });
  }

  detail(value: Statement): void {
    this.run(this.http.get<Entry[]>(`${this.statementPath}/${value.id}/entries`), (entries) => {
      this.selected.set(value.id);
      this.entries.set(entries);
    });
  }

  reload(): void {
    const pending = this.admin ? `${this.adminBase}/settlement/pending` : '/api/merchant/settlement/pending';
    this.run(
      forkJoin({ statements: this.http.get<Statement[]>(this.statementPath), pending: this.http.get<Entry[]>(pending) }),
      (value) => {
        this.statements.set(value.statements);
        this.pending.set(value.pending);
      },
    );
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
    request.pipe(finalize(() => this.busy.set(false))).subscribe({
      next,
      error: (error) => this.message.set(error.error?.message || '操作失败 / Request failed'),
    });
  }
}
