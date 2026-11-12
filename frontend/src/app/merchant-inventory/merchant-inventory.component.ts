import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { InventoryMovement, MerchantInventoryItem } from 'src/model/IInventory';
import { ApiService } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-merchant-inventory',
  templateUrl: './merchant-inventory.component.html',
  styleUrls: ['./merchant-inventory.component.css']
})
export class MerchantInventoryComponent implements OnInit {
  items: MerchantInventoryItem[] = [];
  movements: Record<number, InventoryMovement[]> = {};
  expanded = new Set<number>();
  delta: Record<number, number | null> = {};
  reason: Record<number, string> = {};
  saving = new Set<number>();
  notice = '';
  error = '';

  constructor(private api: ApiService, public i18n: I18nService,
      private changeDetector: ChangeDetectorRef) {}

  ngOnInit(): void { this.load(); }

  get totalAvailable(): number { return this.items.reduce((total, item) => total + item.stockAvailable, 0); }

  load(): void {
    this.api.merchantInventory().subscribe({
      next: items => { this.items = items; this.error = ''; this.changeDetector.markForCheck(); },
      error: () => { this.error = this.i18n.t('inventoryLoadFailed'); this.changeDetector.markForCheck(); }
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
    if (!delta || !reason || this.saving.has(item.productId)) return;
    this.saving.add(item.productId);
    this.api.adjustMerchantInventory(item.productId, delta, reason).subscribe({
      next: updated => {
        this.items = this.items.map(value => value.productId === updated.productId ? updated : value);
        this.delta[item.productId] = null;
        this.reason[item.productId] = '';
        this.notice = this.i18n.t('inventoryAdjusted', {product: updated.productName});
        this.error = '';
        this.saving.delete(item.productId);
        this.loadMovements(item.productId);
        this.changeDetector.markForCheck();
      },
      error: response => {
        this.error = response?.error?.message || this.i18n.t('inventoryAdjustmentFailed');
        this.saving.delete(item.productId);
        this.changeDetector.markForCheck();
      }
    });
  }

  private loadMovements(productId: number): void {
    this.api.merchantInventoryMovements(productId).subscribe(values => {
      this.movements[productId] = values;
      this.changeDetector.markForCheck();
    });
  }
}
