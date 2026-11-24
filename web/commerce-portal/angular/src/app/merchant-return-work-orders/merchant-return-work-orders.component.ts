import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { distinctUntilChanged, finalize, map, Observable, Subscription, timeout, TimeoutError } from 'rxjs';
import { AccountService } from '../services/account.service';
import {
  AfterSaleListCriteria,
  ApiService,
  ReturnWorkDetail,
  ReturnWorkEvidence,
  ReturnWorkTicket,
} from '../services/api.service';

type WorkAction = 'REQUEST_EVIDENCE' | 'REJECT' | 'APPROVE';

interface PendingDecision {
  caseId: string;
  requestId: string;
  action: WorkAction;
  note: string;
  quantity?: number;
}

@Component({
  standalone: false,
  selector: 'app-merchant-return-work-orders',
  templateUrl: './merchant-return-work-orders.component.html',
})
export class MerchantReturnWorkOrdersComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroy = inject(DestroyRef);
  private readonly changes = inject(ChangeDetectorRef);
  private readonly account = inject(AccountService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  readonly admin = this.route.snapshot.data['admin'] === true;
  private readonly merchantId = this.admin ? Number(this.route.snapshot.paramMap.get('id')) : undefined;
  tickets: ReturnWorkTicket[] = [];
  readonly statuses = ['OPEN', 'WAITING_EVIDENCE', 'APPROVED', 'REJECTED'];
  page = 0;
  totalPages = 0;
  private criteria: AfterSaleListCriteria = {};
  detail?: ReturnWorkDetail;
  evidence?: ReturnWorkEvidence | null;
  note = '';
  quantity?: number;
  busy = false;
  loadingDetail = false;
  error = '';
  notice = '';
  evidenceError = false;
  pending?: PendingDecision;
  private selectedCase?: string;
  private detailRequest?: Subscription;

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(
        map((params) => params.get('caseId') ?? undefined),
        distinctUntilChanged(),
        takeUntilDestroyed(this.destroy),
      )
      .subscribe((caseId) => {
        this.detailRequest?.unsubscribe();
        this.selectedCase = caseId;
        this.detail = this.evidence = undefined;
        this.notice = this.note = '';
        this.quantity = undefined;
        this.pending = caseId && !this.admin ? this.readPending(caseId) : undefined;
        if (caseId) this.checkResult();
      });
    if (!this.admin) this.refresh();
  }

  applyCriteria(criteria: AfterSaleListCriteria): void {
    this.criteria = criteria;
    this.refresh(0);
  }

  refresh(page = this.page): void {
    this.run(this.api.returnWorkOrders(this.criteria, page), (result) => {
      this.tickets = result.rows;
      this.page = result.page;
      this.totalPages = Math.ceil(result.total / result.size);
      const selected = result.rows.find((item) => item.caseId === this.detail?.ticket.caseId);
      if (selected && this.detail) this.detail = { ...this.detail, ticket: selected };
    });
  }

  select(ticket: ReturnWorkTicket): void {
    if (this.selectedCase === ticket.caseId) this.checkResult();
    else void this.router.navigate([], { relativeTo: this.route, queryParams: { caseId: ticket.caseId } });
  }

  checkResult(): void {
    const id = this.selectedCase;
    if (!id || this.loadingDetail) return;
    this.loadingDetail = true;
    this.error = '';
    this.detailRequest = this.api
      .returnWorkOrder(id, this.merchantId)
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => {
          this.loadingDetail = false;
          this.changes.markForCheck();
        }),
      )
      .subscribe({
        next: (detail) => {
          this.syncDetail(detail);
          if (detail.ticket.warehouseStatus !== 'REJECTED') this.loadEvidence();
        },
        error: (error) => {
          if (error.status === 401 || error.status === 403 || error.status === 404) {
            this.detail = this.evidence = this.pending = undefined;
          }
          this.error = error.error?.message ?? 'requestFailed';
        },
      });
  }

  loadEvidence(): void {
    const id = this.detail?.ticket.caseId;
    if (!id) return;
    this.evidenceError = false;
    this.api
      .returnWorkEvidence(id, this.merchantId)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (evidence) => {
          if (this.detail?.ticket.caseId !== id) return;
          this.evidence = evidence;
          if (evidence && !this.pending)
            this.quantity = Math.min(evidence.qualifiedQuantity, this.detail.ticket.requestedQuantity);
          this.changes.markForCheck();
        },
        error: () => {
          if (this.detail?.ticket.caseId === id) this.evidenceError = true;
          this.changes.markForCheck();
        },
      });
  }

  decide(action: WorkAction): void {
    const ticket = this.detail?.ticket;
    if (this.admin || !ticket || this.pending || this.busy || !this.note.trim()) return;
    const quantity = action === 'APPROVE' ? this.quantity : undefined;
    this.send({ caseId: ticket.caseId, requestId: crypto.randomUUID(), action, note: this.note.trim(), quantity });
  }

  retry(): void {
    if (this.pending && this.detail?.ticket.caseId === this.pending.caseId && !this.busy) this.send(this.pending);
  }

  private send(pending: PendingDecision): void {
    const { caseId, ...body } = pending;
    this.persist(caseId, pending);
    this.notice = '';
    this.run(
      this.api.decideReturnWork(caseId, body).pipe(timeout(8000)),
      (detail) => {
        if (this.selectedCase === caseId) this.syncDetail(detail);
        else this.persist(caseId);
      },
      (error: HttpErrorResponse | TimeoutError) => {
        if (error instanceof TimeoutError || error.status === 0 || error.status >= 500) {
          if (this.selectedCase === caseId) this.notice = 'returnWorkUnknown';
          return;
        }
        this.persist(caseId);
        if (this.selectedCase === caseId) this.error = error.error?.message ?? 'requestFailed';
      },
    );
  }

  private syncDetail(detail: ReturnWorkDetail): void {
    this.detail = detail;
    const ticket = detail.ticket;
    this.tickets = this.tickets.map((item) => (item.caseId === ticket.caseId ? ticket : item));
    const pending = this.pending;
    if (!pending) return;
    if (detail.actions.some((item) => item.requestId === pending.requestId)) {
      this.persist(ticket.caseId);
      this.note = '';
      this.notice = 'returnWorkConfirmed';
    } else if (!['OPEN', 'WAITING_EVIDENCE'].includes(ticket.phase)) {
      this.persist(ticket.caseId);
      this.note = '';
      this.notice = 'returnWorkChanged';
    } else {
      this.note = pending.note;
      this.quantity = pending.quantity;
      this.notice = 'returnWorkUnknown';
    }
  }

  private readPending(id: string): PendingDecision | undefined {
    try {
      const stored = sessionStorage.getItem(this.storageKey(id));
      return stored ? (JSON.parse(stored) as PendingDecision) : undefined;
    } catch {
      return undefined;
    }
  }

  private persist(id: string, value?: PendingDecision): void {
    if (this.selectedCase === id) this.pending = value;
    try {
      if (value) sessionStorage.setItem(this.storageKey(id), JSON.stringify(value));
      else sessionStorage.removeItem(this.storageKey(id));
    } catch {
      // Keep the in-memory request when tab storage is unavailable.
    }
  }

  private storageKey(id: string): string {
    return 'commerceflow-return-work:' + this.account.userProfile()?.id + ':' + id;
  }

  status(ticket: ReturnWorkTicket): string {
    if (ticket.phase !== 'APPROVED') return ticket.phase;
    if (ticket.caseStatus === 'COMPLETED') return 'RESOLVED';
    if (['FAILED', 'REVIEW_REQUIRED'].includes(ticket.caseStatus)) return 'FAILED';
    return 'PROCESSING';
  }

  private run<T>(
    request: Observable<T>,
    onSuccess: (value: T) => void,
    onError?: (error: HttpErrorResponse) => void,
  ): void {
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
        error: onError ?? ((error) => (this.error = error.error?.message ?? 'requestFailed')),
      });
  }
}
