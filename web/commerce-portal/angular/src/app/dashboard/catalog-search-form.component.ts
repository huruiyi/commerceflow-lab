import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CatalogStore } from '../services/api.service';
import { I18nService } from '../services/i18n.service';
import {
  CatalogQuery,
  catalogMatchOptions,
  invalidCatalogKeyword,
  invalidCatalogPrice,
  invalidCatalogSku,
} from './catalog-query';

@Component({
  selector: 'app-catalog-search-form',
  imports: [FormsModule],
  templateUrl: './catalog-search-form.component.html',
  styleUrl: './catalog-search-form.component.css',
})
export class CatalogSearchFormComponent {
  readonly draft = input.required<CatalogQuery>();
  readonly stores = input<readonly CatalogStore[]>();
  readonly searched = output<void>();
  readonly cleared = output<void>();
  readonly i18n = inject(I18nService);
  readonly matchOptions = catalogMatchOptions;
  readonly advancedOpen = signal(false);

  constructor() {
    effect(() => {
      const draft = this.draft();
      if (invalidCatalogPrice(draft) || invalidCatalogSku(draft.sku) || invalidCatalogKeyword(draft.exclude)) {
        this.advancedOpen.set(true);
      }
    });
  }

  get activeAdvancedCount(): number {
    const { match, exclude, merchantId, minPrice, maxPrice, sku } = this.draft();
    return (
      Number(match !== 'all') +
      Number(Boolean(exclude.trim())) +
      Number(merchantId !== null) +
      Number(minPrice !== null || maxPrice !== null) +
      Number(Boolean(sku)) +
      Number(Boolean(this.draft().attributes))
    );
  }

  unknownStore(merchantId: number): boolean {
    return !this.stores()?.some((store) => store.merchantId === merchantId);
  }

  get hasDraft(): boolean {
    return Boolean(this.draft().q.trim()) || this.activeAdvancedCount > 0 || this.draft().sort !== 'default';
  }

  get invalidKeyword(): boolean {
    return invalidCatalogKeyword(this.draft().q);
  }

  get invalidExclude(): boolean {
    return invalidCatalogKeyword(this.draft().exclude);
  }

  get invalidPrice(): boolean {
    return invalidCatalogPrice(this.draft());
  }

  get invalidSku(): boolean {
    return invalidCatalogSku(this.draft().sku);
  }

  search(): void {
    if (this.invalidKeyword || this.invalidExclude || this.invalidPrice || this.invalidSku) return;
    this.advancedOpen.set(false);
    this.searched.emit();
  }

  clear(): void {
    this.advancedOpen.set(false);
    this.cleared.emit();
  }
}
