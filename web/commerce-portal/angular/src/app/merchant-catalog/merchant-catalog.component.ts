import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { ApiService, MerchantProduct, ShippingTemplate } from '../services/api.service';

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
  creation = { name: '', description: '', unitPrice: 0.01, imageUrl: '' };
  private createRequest?: { key: string; content: string };
  shipping?: ShippingTemplate;
  readonly shippingBusy = signal(false);
  readonly shippingError = signal('');
  readonly shippingSaved = signal(false);

  loadShipping(): void {
    if (this.shippingBusy()) return;
    this.shippingBusy.set(true);
    this.shippingError.set('');
    this.api
      .shippingTemplate()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.shippingBusy.set(false)),
      )
      .subscribe({
        next: (template) => (this.shipping = template),
        error: () => this.shippingError.set('requestFailed'),
      });
  }

  saveShipping(template: ShippingTemplate): void {
    if (this.shippingBusy()) return;
    this.shippingBusy.set(true);
    this.shippingError.set('');
    this.shippingSaved.set(false);
    this.api
      .saveShippingTemplate(template)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.shippingBusy.set(false)),
      )
      .subscribe({
        next: (updated) => {
          this.shipping = updated;
          this.shippingSaved.set(true);
        },
        error: () => this.shippingError.set('shippingSaveFailed'),
      });
  }

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

  create(): void {
    if (this.saving()) return;
    const content = JSON.stringify(this.creation);
    if (this.createRequest?.content !== content) this.createRequest = { key: crypto.randomUUID(), content };
    this.saving.set(true);
    this.saveFailed.set(false);
    this.api.createMerchantProduct({ ...this.creation, requestId: this.createRequest.key })
      .pipe(takeUntilDestroyed(this.destroyRef), finalize(() => this.saving.set(false)))
      .subscribe({
        next: (product) => {
          this.products.update((items) => [...items.filter((item) => item.productId !== product.productId), product]);
          this.creation = { name: '', description: '', unitPrice: 0.01, imageUrl: '' };
          this.createRequest = undefined;
          this.edit(product);
        },
        error: () => this.saveFailed.set(true),
      });
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
