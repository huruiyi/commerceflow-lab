import { AdminListPage } from 'src/model/IAdminListPage';
import { finalize } from 'rxjs';
import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { InventoryMovement, MerchantInventoryItem } from 'src/model/IInventory';
import { ApiService } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-merchant-inventory',
  templateUrl: './merchant-inventory.component.html',
  styleUrls: ['./merchant-inventory.component.css'],
})
export class MerchantInventoryComponent implements OnInit {
  items: MerchantInventoryItem[] = [];
  movementPages: Record<number, AdminListPage<InventoryMovement> | undefined> = {};
  readonly loadingMovements = new Set<number>();
  readonly failedMovements = new Map<number, number>();
  expanded = new Set<number>();
  delta: Record<number, number | null> = {};
  reason: Record<number, string> = {};
  warehouse: Record<number, number> = {};
  saving = new Set<number>();
  notice = '';
  error = '';

  constructor(
    private api: ApiService,
    public i18n: I18nService,
    private changeDetector: ChangeDetectorRef,
  ) {}

  ngOnInit(): void {
    this.load();
  }

  get totalAvailable(): number {
    return this.items.reduce((total, item) => total + item.stockAvailable, 0);
  }

  load(): void {
    this.api.merchantInventory().subscribe({
      next: (items) => {
        this.items = items;
        for (const { productId } of items) {
          if (this.expanded.has(productId)) this.loadMovements(productId, this.movementPages[productId]?.page);
        }
        this.error = '';
        this.changeDetector.markForCheck();
      },
      error: () => {
        this.error = this.i18n.t('inventoryLoadFailed');
        this.changeDetector.markForCheck();
      },
    });
  }

  toggle(item: MerchantInventoryItem): void {
    if (this.expanded.delete(item.productId)) return;
    this.expanded.add(item.productId);
    this.loadMovements(item.productId);
  }

  adjust(item: MerchantInventoryItem): void {
    const delta = this.delta[item.productId];
    const reason = (this.reason[item.productId] || '').trim();
    if (!delta || !reason || this.saving.has(item.productId) || this.loadingMovements.has(item.productId)) return;
    this.saving.add(item.productId);
    this.api.adjustMerchantInventory(item.productId, delta, reason, this.warehouse[item.productId] ?? 1).subscribe({
      next: (updated) => {
        this.items = this.items.map((value) => (value.productId === updated.productId ? updated : value));
        this.delta[item.productId] = null;
        this.reason[item.productId] = '';
        this.notice = this.i18n.t('inventoryAdjusted', { product: updated.productName });
        this.error = '';
        this.saving.delete(item.productId);
        this.loadMovements(item.productId);
        this.changeDetector.markForCheck();
      },
      error: (response) => {
        this.error = response?.error?.message || this.i18n.t('inventoryAdjustmentFailed');
        this.saving.delete(item.productId);
        this.changeDetector.markForCheck();
      },
    });
  }

  loadMovements(productId: number, page = 0): void {
    if (this.loadingMovements.has(productId) || this.saving.has(productId)) return;
    this.loadingMovements.add(productId);
    this.failedMovements.delete(productId);
    this.api
      .merchantInventoryMovements(productId, page)
      .pipe(
        finalize(() => {
          this.loadingMovements.delete(productId);
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (value) => (this.movementPages[productId] = value),
        error: () => this.failedMovements.set(productId, page),
      });
  }

  retryMovements(productId: number): void {
    this.loadMovements(productId, this.failedMovements.get(productId));
  }

  movementPageCount(page: AdminListPage<InventoryMovement>): number {
    return Math.ceil(page.total / page.size);
  }
}
