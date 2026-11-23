import { HttpErrorResponse } from '@angular/common/http';
import {
  afterNextRender,
  ChangeDetectorRef,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, finalize } from 'rxjs';
import {
  InventoryRecovery,
  StocktakeBaseline,
  StocktakeReview,
  WarehouseEvidenceContext,
  WarehouseEvidenceLine,
  WarehouseEvidenceReceipt,
} from 'src/model/IInventoryReconciliation';
import { ApiService } from '../services/api.service';

interface EvidenceDraft {
  warehouseReference: string;
  evidenceAt: string;
  note: string;
  mode: '' | 'existing' | 'outbound';
  bookedMovementId: number | null;
  expectedAvailable: number | null;
}

@Component({
  standalone: false,
  selector: 'app-inventory-reconciliation-admin',
  templateUrl: './inventory-reconciliation.component.html',
  styleUrls: ['./inventory-reconciliation.component.css'],
})
export class InventoryReconciliationAdminComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly api = inject(ApiService);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly injector = inject(Injector);
  private readonly queuePageSize = 10;
  private requestedQueuePage = 0;

  recoveries: InventoryRecovery[] = [];
  queueQuery = '';
  selected: InventoryRecovery | null = null;
  context: WarehouseEvidenceContext | null = null;
  drafts: EvidenceDraft[] = [];
  receipt: WarehouseEvidenceReceipt | null = null;
  loading = false;
  loadingDetail = false;
  saving = false;
  listError = false;
  detailError = '';
  submitError = '';
  stocktakeOpen = false;
  stocktakeBusy = false;
  stocktakeError = '';
  stocktakeReference = '';
  stocktakeLookup = '';
  stocktakeProductId: number | null = null;
  stocktakeCounted: number | null = null;
  stocktakeTime = '';
  stocktakeNote = '';
  stocktakeApprovalReason = '';
  stocktakeCases = new Set<string>();
  countBaseline: StocktakeBaseline | null = null;
  countReview: StocktakeReview | null = null;

  ngOnInit(): void {
    this.load();
  }

  get queueView() {
    const query = this.queueQuery.trim().replace(/^#/, '');
    const matches = query ? this.recoveries.filter((item) => String(item.orderId).includes(query)) : this.recoveries;
    const totalPages = Math.ceil(matches.length / this.queuePageSize);
    const page = Math.min(this.requestedQueuePage, Math.max(0, totalPages - 1));
    const start = page * this.queuePageSize;
    return { total: matches.length, page, totalPages, rows: matches.slice(start, start + this.queuePageSize) };
  }

  filterQueue(query: string): void {
    this.queueQuery = query;
    this.requestedQueuePage = 0;
  }

  changeQueuePage(page: number): void {
    this.requestedQueuePage = page;
  }

  toggleStocktake(): void {
    this.stocktakeOpen = !this.stocktakeOpen;
    this.focusHeading(this.stocktakeOpen ? '#stocktake-title' : '#stocktake-toggle');
  }

  private focusHeading(selector: string): void {
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  toggleStocktakeCase(commandId: string, checked: boolean): void {
    if (checked) this.stocktakeCases.add(commandId);
    else this.stocktakeCases.delete(commandId);
  }

  captureBaseline(): void {
    const productId = this.stocktakeProductId;
    if (productId === null || !Number.isSafeInteger(productId) || productId <= 0) return;
    this.countBaseline = null;
    this.runStocktake(this.api.stocktakeBaseline(productId), (baseline) => (this.countBaseline = baseline));
  }

  submitCount(): void {
    const baseline = this.countBaseline;
    const counted = this.stocktakeCounted;
    const countedAt = new Date(this.stocktakeTime);
    const reference = this.stocktakeReference.trim();
    if (
      !baseline ||
      baseline.productId !== this.stocktakeProductId ||
      counted === null ||
      !Number.isInteger(counted) ||
      counted < 0 ||
      counted > 2147483647 ||
      !Number.isFinite(countedAt.getTime()) ||
      !this.stocktakeCases.size ||
      !/^[A-Za-z0-9_-]{1,80}$/.test(reference) ||
      !this.stocktakeNote.trim()
    ) {
      this.stocktakeError = 'stocktakeInvalid';
      return;
    }
    this.runStocktake(
      this.api.submitStocktake({
        reference,
        productId: baseline.productId,
        baseline,
        countedAvailable: counted,
        countedAt: countedAt.toISOString(),
        note: this.stocktakeNote.trim(),
        commandIds: [...this.stocktakeCases],
      }),
      (review) => {
        this.stocktakeLookup = review.proposal.reference;
        this.countReview = review;
      },
    );
  }

  loadCountReview(): void {
    const reference = this.stocktakeLookup.trim();
    if (!reference) return;
    this.countReview = null;
    this.stocktakeApprovalReason = '';
    this.runStocktake(this.api.stocktakeReview(reference), (review) => (this.countReview = review));
  }

  confirmCount(): void {
    const review = this.countReview;
    const reason = this.stocktakeApprovalReason.trim();
    if (!review || review.receipt || !reason) return;
    const reference = review.proposal.reference;
    this.runStocktake(this.api.approveStocktake(reference, reason), (approved) => {
      this.countReview = approved;
      this.stocktakeApprovalReason = '';
      this.stocktakeCases.clear();
      this.load();
    });
  }

  private runStocktake<T>(request: Observable<T>, next: (value: T) => void): void {
    if (this.stocktakeBusy) return;
    this.stocktakeBusy = true;
    this.stocktakeError = '';
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.stocktakeBusy = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next,
        error: (error: HttpErrorResponse) => (this.stocktakeError = this.errorMessage(error)),
      });
  }

  load(): void {
    this.loading = true;
    this.listError = false;
    this.api
      .inventoryRecoveries()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.loading = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (recoveries) => (this.recoveries = recoveries),
        error: () => (this.listError = true),
      });
  }

  open(recovery: InventoryRecovery): void {
    this.selected = recovery;
    this.focusHeading('#warehouse-editor-title');
    this.context = null;
    this.drafts = [];
    this.detailError = '';
    this.submitError = '';
    this.receipt = null;
    this.loadingDetail = true;
    this.api
      .warehouseEvidenceContext(recovery.commandId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.loadingDetail = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (context) => {
          this.context = context;
          this.drafts = context.products.map(() => ({
            warehouseReference: '',
            evidenceAt: '',
            note: '',
            mode: '',
            bookedMovementId: null,
            expectedAvailable: null,
          }));
        },
        error: (error: HttpErrorResponse) => {
          this.detailError = error.status === 409 ? 'warehouseIneligible' : this.errorMessage(error);
        },
      });
  }

  submit(): void {
    if (!this.selected || !this.context || this.saving) return;
    const context = this.context;
    const lines = this.drafts.map((draft, index) => this.buildLine(draft, context, index));
    if (!lines.length || lines.some((line) => line === null)) {
      this.submitError = 'warehouseInvalidEvidence';
      return;
    }
    const references = new Set(lines.map((line) => line?.warehouseReference.toLocaleLowerCase()));
    if (references.size !== lines.length) {
      this.submitError = 'warehouseDuplicateReference';
      return;
    }
    this.saving = true;
    this.submitError = '';
    const commandId = this.selected.commandId;
    this.api
      .submitWarehouseEvidence(commandId, lines as WarehouseEvidenceLine[])
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.saving = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (receipt) => {
          this.receipt = receipt;
          this.recoveries = this.recoveries.filter((recovery) => recovery.commandId !== commandId);
          this.selected = null;
          this.context = null;
          this.drafts = [];
        },
        error: (error: HttpErrorResponse) => (this.submitError = this.errorMessage(error)),
      });
  }

  private buildLine(
    draft: EvidenceDraft,
    context: WarehouseEvidenceContext,
    index: number,
  ): WarehouseEvidenceLine | null {
    const product = context.products[index];
    const evidenceTime = new Date(draft.evidenceAt);
    const timestamp = evidenceTime.getTime();
    const warehouseReference = draft.warehouseReference.trim();
    const note = draft.note.trim();
    if (
      !warehouseReference ||
      warehouseReference.length > 120 ||
      !note ||
      note.length > 300 ||
      !Number.isFinite(timestamp) ||
      timestamp < Date.parse(context.createdAt) ||
      timestamp > Date.now()
    ) {
      return null;
    }
    const line = { productId: product.productId, warehouseReference, note, evidenceAt: evidenceTime.toISOString() };
    if (
      draft.mode === 'existing' &&
      draft.bookedMovementId !== null &&
      Number.isSafeInteger(draft.bookedMovementId) &&
      draft.bookedMovementId > 0
    ) {
      return { ...line, bookedMovementId: draft.bookedMovementId };
    }
    if (
      draft.mode === 'outbound' &&
      draft.expectedAvailable !== null &&
      Number.isInteger(draft.expectedAvailable) &&
      draft.expectedAvailable >= product.quantity &&
      draft.expectedAvailable <= 2147483647
    ) {
      return { ...line, expectedAvailable: draft.expectedAvailable };
    }
    return null;
  }

  private errorMessage(error: HttpErrorResponse): string {
    return error.error?.message || error.error?.detail || 'requestFailed';
  }
}
