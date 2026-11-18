import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import {
  InventoryRecovery,
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
  templateUrl: './inventory-reconciliation-admin.component.html',
  styleUrls: ['./inventory-reconciliation-admin.component.css'],
})
export class InventoryReconciliationAdminComponent implements OnInit {
  private readonly destroyRef = inject(DestroyRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly api = inject(ApiService);

  recoveries: InventoryRecovery[] = [];
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

  ngOnInit(): void {
    this.load();
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
