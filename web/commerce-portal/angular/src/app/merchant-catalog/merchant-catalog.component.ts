import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { ApiService, MerchantProduct } from '../services/api.service';

@Component({
  standalone: false,
  selector: 'app-merchant-catalog',
  templateUrl: './merchant-catalog.component.html',
})
export class MerchantCatalogComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly products = signal<MerchantProduct[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly saveFailed = signal(false);
  readonly saved = signal(false);
  draft?: MerchantProduct;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadFailed.set(false);
    this.api
      .merchantProducts()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (items) => {
          this.products.set(items);
          if (this.draft) this.draft = items.find((item) => item.productId === this.draft?.productId);
          if (this.draft) this.edit(this.draft);
        },
        error: () => this.loadFailed.set(true),
      });
  }

  edit(product: MerchantProduct): void {
    this.draft = { ...product };
    this.saveFailed.set(false);
    this.saved.set(false);
  }

  save(area: 'profile' | 'price' | 'status', product: MerchantProduct): void {
    if (this.saving() || this.loading()) return;
    const expectedVersion = product.businessVersion;
    const { name, brief, description, imageUrl, thumbnailUrl, unitPrice, lifecycleStatus } = product;
    const updates: Record<typeof area, object> = {
      profile: { expectedVersion, name, brief, description, imageUrl, thumbnailUrl },
      price: { expectedVersion, unitPrice },
      status: { expectedVersion, status: lifecycleStatus },
    };
    this.saving.set(true);
    this.saveFailed.set(false);
    this.saved.set(false);
    const request: Observable<MerchantProduct> = this.api.updateMerchantProduct(product.productId, area, updates[area]);
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: (updated) => {
          this.products.update((items) => items.map((item) => (item.productId === updated.productId ? updated : item)));
          this.draft = { ...product, businessVersion: updated.businessVersion };
          this.saved.set(true);
        },
        error: () => this.saveFailed.set(true),
      });
  }
}
