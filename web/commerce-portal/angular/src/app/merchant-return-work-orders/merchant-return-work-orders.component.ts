import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { ApiService, ReturnWorkDetail, ReturnWorkEvidence, ReturnWorkTicket } from '../services/api.service';

@Component({
  standalone: false,
  selector: 'app-merchant-return-work-orders',
  templateUrl: './merchant-return-work-orders.component.html',
})
export class MerchantReturnWorkOrdersComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroy = inject(DestroyRef);
  private readonly changes = inject(ChangeDetectorRef);
  tickets: ReturnWorkTicket[] = [];
  detail?: ReturnWorkDetail;
  evidence?: ReturnWorkEvidence | null;
  note = '';
  quantity?: number;
  busy = false;
  error = '';
  evidenceError = false;
  private retry?: { key: string; id: string };

  ngOnInit(): void {
    this.refresh();
  }

  refresh(): void {
    this.run(this.api.returnWorkOrders(), (tickets) => {
      this.tickets = tickets;
      const selected = tickets.find((item) => item.caseId === this.detail?.ticket.caseId);
      if (selected && this.detail) this.detail = { ...this.detail, ticket: selected };
    });
  }

  select(ticket: ReturnWorkTicket): void {
    this.run(this.api.returnWorkOrder(ticket.caseId), (detail) => {
      this.detail = detail;
      this.evidence = undefined;
      this.evidenceError = false;
      this.note = '';
      this.quantity = undefined;
      if (ticket.warehouseStatus !== 'REJECTED') this.loadEvidence();
    });
  }

  loadEvidence(): void {
    const id = this.detail?.ticket.caseId;
    if (!id) return;
    this.evidenceError = false;
    this.api
      .returnWorkEvidence(id)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (evidence) => {
          if (this.detail?.ticket.caseId !== id) return;
          this.evidence = evidence;
          if (evidence) this.quantity = Math.min(evidence.qualifiedQuantity, this.detail.ticket.requestedQuantity);
          this.changes.markForCheck();
        },
        error: () => {
          if (this.detail?.ticket.caseId === id) this.evidenceError = true;
          this.changes.markForCheck();
        },
      });
  }

  decide(action: 'REQUEST_EVIDENCE' | 'REJECT' | 'APPROVE'): void {
    const ticket = this.detail?.ticket;
    if (!ticket || !this.note.trim()) return;
    const quantity = action === 'APPROVE' ? this.quantity : undefined;
    const payload = { action, note: this.note.trim(), quantity };
    const key = JSON.stringify([ticket.caseId, payload]);
    const requestId = this.retry?.key === key ? this.retry.id : crypto.randomUUID();
    this.retry = { key, id: requestId };
    this.run(this.api.decideReturnWork(ticket.caseId, { requestId, ...payload }), (detail) => {
      this.detail = detail;
      this.tickets = this.tickets.map((item) => (item.caseId === ticket.caseId ? detail.ticket : item));
      this.note = '';
      this.retry = undefined;
    });
  }

  status(ticket: ReturnWorkTicket): string {
    if (ticket.phase !== 'APPROVED') return ticket.phase;
    if (ticket.caseStatus === 'COMPLETED') return 'RESOLVED';
    if (['FAILED', 'REVIEW_REQUIRED'].includes(ticket.caseStatus)) return 'FAILED';
    return 'PROCESSING';
  }

  private run<T>(request: Observable<T>, onSuccess: (value: T) => void): void {
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
        next: onSuccess,
        error: (error) => (this.error = error.error?.message ?? 'requestFailed'),
      });
  }
}
