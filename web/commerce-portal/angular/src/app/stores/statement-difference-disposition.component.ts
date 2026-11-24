import { DatePipe, DOCUMENT } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, input, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize, Observable } from 'rxjs';

interface Disposition {
  route: 'STOCKTAKE' | 'PAYOUT' | 'INVOICE' | 'MANUAL';
  status: 'OPEN' | 'EVIDENCE_LINKED' | 'CLOSED';
  record: {
    receipt: string | null;
    linkedBy: number | null;
    linkedAt: string | null;
    closedBy: number | null;
    closedAt: string | null;
    closureReason: string | null;
  } | null;
}

@Component({
  selector: 'app-statement-difference-disposition',
  imports: [DatePipe, FormsModule, RouterLink],
  templateUrl: './statement-difference-disposition.component.html',
})
export class StatementDifferenceDispositionComponent implements OnInit {
  readonly path = input.required<string>();
  readonly orderId = input.required<number | null>();
  readonly noAction = input.required<boolean>();
  readonly state = signal<Disposition | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  receipt = '';
  reason = '';
  private draft?: { payload: string; requestId: string };
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private readonly document = inject(DOCUMENT);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.run(this.http.get<Disposition>(this.path()));
  }

  link(): void {
    const receipt = this.receipt.trim();
    if (receipt) this.submit('receipts', { receipt });
  }

  close(): void {
    const reason = this.reason.trim();
    if (reason) this.submit('closure', { reason });
  }

  focusPayout(): void {
    this.document.getElementById('statement-payouts')?.focus();
  }

  private submit(action: string, body: Record<string, string>): void {
    const payload = JSON.stringify({ action, body });
    if (this.draft?.payload !== payload) this.draft = { payload, requestId: crypto.randomUUID() };
    this.run(this.http.post<Disposition>(this.path() + '/' + action, { ...body, requestId: this.draft.requestId }));
  }

  private run(request: Observable<Disposition>): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: (value) => {
          this.state.set(value);
          this.draft = undefined;
        },
        error: (error: HttpErrorResponse) =>
          this.error.set(error.error?.message || '处置暂不可用，请重试 / Disposition unavailable. Retry.'),
      });
  }
}
