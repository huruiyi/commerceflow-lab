import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectorRef, Component, DestroyRef, inject, OnDestroy, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { finalize, Observable, Subscription } from 'rxjs';
import { InvoiceApi, InvoiceDetail, InvoiceMode, InvoicePage, invoiceRoutes } from './invoice-api.service';

@Component({
  standalone: false,
  selector: 'app-invoice-center',
  templateUrl: './invoice-center.component.html',
  styleUrl: './invoice-center.component.css',
})
export class InvoiceCenterComponent implements OnInit, OnDestroy {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(InvoiceApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private loadRequest?: Subscription;
  private refreshTimer?: ReturnType<typeof setTimeout>;
  private scopeVersion = 0;

  readonly mode = this.route.snapshot.data['invoiceMode'] as InvoiceMode;
  readonly root = invoiceRoutes[this.mode];
  readonly statusOptions = ['PENDING', 'ISSUED', 'EXHAUSTED', 'REVIEW_REQUIRED'];
  readonly heading = {
    customer: 'invoiceCustomer',
    merchant: 'invoiceMerchant',
    platform: 'invoicePlatform',
  }[this.mode];

  orderId: number | null = null;
  page: InvoicePage | null = null;
  detail: InvoiceDetail | null = null;
  busy = false;
  acting = false;
  error = '';
  actionError = '';
  reviewNote = '';
  term = '';
  status = '';
  private appliedTerm = '';
  private appliedStatus = '';
  private pageIndex = 0;

  ngOnInit(): void {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.scopeVersion++;
      this.orderId = params.has('id') ? Number(params.get('id')) : null;
      this.detail = null;
      this.page = null;
      this.reviewNote = '';
      this.acting = false;
      this.actionError = '';
      this.reload();
    });
  }

  ngOnDestroy(): void {
    this.scopeVersion++;
    this.loadRequest?.unsubscribe();
    clearTimeout(this.refreshTimer);
  }

  reload(silent = false): void {
    this.loadRequest?.unsubscribe();
    clearTimeout(this.refreshTimer);
    this.error = '';
    this.busy = !silent;
    if (this.orderId === null) {
      this.load(this.api.list(this.mode, this.appliedTerm, this.appliedStatus, this.pageIndex), (page) => {
        this.page = page;
      });
    } else if (!Number.isSafeInteger(this.orderId) || this.orderId <= 0) {
      this.busy = false;
      this.error = 'invoiceUnavailable';
    } else {
      this.load(this.api.detail(this.mode, this.orderId), (detail) => this.setDetail(detail));
    }
  }

  filter(): void {
    this.appliedTerm = this.term;
    this.appliedStatus = this.status;
    this.changePage(0);
  }

  changePage(index: number): void {
    this.pageIndex = index;
    this.reload();
  }

  detailLink(orderId: number): (string | number)[] {
    return this.mode === 'customer' ? ['/orders', orderId, 'invoices'] : [this.root, orderId];
  }

  request(): void {
    if (this.orderId !== null && this.detail?.eligible) this.act(this.api.request(this.orderId));
  }

  recheck(): void {
    if (this.orderId !== null && this.detail?.status === 'REVIEW_REQUIRED' && this.reviewNote.trim()) {
      this.act(this.api.recheck(this.orderId, this.reviewNote.trim()));
    }
  }

  private act(request: Observable<InvoiceDetail>): void {
    if (this.acting) return;
    this.loadRequest?.unsubscribe();
    clearTimeout(this.refreshTimer);
    const scope = this.scopeVersion;
    this.acting = true;
    this.actionError = '';
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (scope === this.scopeVersion) {
            this.acting = false;
            this.changeDetector.markForCheck();
          }
        }),
      )
      .subscribe({
        next: (detail) => {
          if (scope === this.scopeVersion) {
            this.reviewNote = '';
            this.setDetail(detail);
            if (this.mode === 'platform' && detail.status !== 'PENDING') {
              this.refreshTimer = setTimeout(() => this.reload(true), 1000);
            }
          }
        },
        error: (error) => {
          if (scope === this.scopeVersion) this.actionError = this.errorKey(error, 'invoiceActionFailed');
        },
      });
  }

  private load<T>(request: Observable<T>, success: (value: T) => void): void {
    this.loadRequest = request
      .pipe(
        finalize(() => {
          this.busy = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: success,
        error: (error) => {
          this.detail = null;
          this.page = null;
          this.error = this.errorKey(error, 'invoiceLoadFailed');
        },
      });
  }

  private setDetail(detail: InvoiceDetail): void {
    this.detail = detail;
    clearTimeout(this.refreshTimer);
    if (detail.status === 'PENDING' || detail.redAttempts.some((attempt) => attempt.status === 'PENDING')) {
      this.refreshTimer = setTimeout(() => this.reload(true), 2500);
    }
  }

  private errorKey(error: HttpErrorResponse, fallback: string): string {
    if (error.status === 403) return 'invoiceForbidden';
    if (error.status === 404) return 'invoiceUnavailable';
    if (error.status === 409) return 'invoiceConflict';
    return fallback;
  }
}
