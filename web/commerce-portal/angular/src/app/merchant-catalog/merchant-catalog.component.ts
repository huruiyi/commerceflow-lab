import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, OnInit, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { concatMap, finalize, from, last, tap } from 'rxjs';
import { ApiService, MerchantProduct, ShippingTemplate } from '../services/api.service';

type ProductUpdate = { area: 'profile' | 'price' | 'status'; value: object };

@Component({
  standalone: false,
  selector: 'app-merchant-catalog',
  templateUrl: './merchant-catalog.component.html',
  styleUrl: './merchant-catalog.component.css',
})
export class MerchantCatalogComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly injector = inject(Injector);
  readonly products = signal<MerchantProduct[]>([]);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly saveFailed = signal(false);
  readonly productSaveError = signal<{ partial: boolean; conflict: boolean } | null>(null);
  readonly saved = signal(false);
  draft?: MerchantProduct;
  creationOpen = false;
  creation = { name: '', description: '', unitPrice: 0.01, imageUrl: '' };
  private createRequest?: { key: string; content: string };
  shipping?: ShippingTemplate;
  shippingDraft?: ShippingTemplate;
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

  editShipping(): void {
    if (!this.shipping || this.shippingBusy()) return;
    this.shippingDraft = {
      defaultFee: this.shipping.defaultFee,
      regions: this.shipping.regions.map((region) => ({ ...region })),
    };
    this.shippingError.set('');
    this.shippingSaved.set(false);
  }

  cancelShipping(): void {
    this.shippingDraft = undefined;
    this.shippingError.set('');
  }

  trimShippingLeadingZeros(input: HTMLInputElement): void {
    const amount = input.value.replace(/^0+(?=\d)/u, '');
    if (amount !== input.value) input.value = amount;
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
          this.shippingDraft = undefined;
          this.shippingSaved.set(true);
        },
        error: () => this.shippingError.set('shippingSaveFailed'),
      });
  }

  ngOnInit(): void {
    this.load();
    this.loadShipping();
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

  edit(product: MerchantProduct, focus = false): void {
    this.draft = { ...product };
    this.saveFailed.set(false);
    this.productSaveError.set(null);
    this.saved.set(false);
    if (focus) {
      afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('#merchant-edit-title')?.focus(), {
        injector: this.injector,
      });
    }
  }

  create(): void {
    if (this.saving()) return;
    const content = JSON.stringify(this.creation);
    if (this.createRequest?.content !== content) this.createRequest = { key: crypto.randomUUID(), content };
    this.saving.set(true);
    this.saveFailed.set(false);
    this.api
      .createMerchantProduct({ ...this.creation, requestId: this.createRequest.key })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: (product) => {
          this.products.update((items) => [...items.filter((item) => item.productId !== product.productId), product]);
          this.creation = { name: '', description: '', unitPrice: 0.01, imageUrl: '' };
          this.createRequest = undefined;
          this.creationOpen = false;
          this.edit(product, true);
        },
        error: () => this.saveFailed.set(true),
      });
  }

  hasProductChanges(product: MerchantProduct): boolean {
    return this.productUpdates(product).length > 0;
  }

  saveProduct(product: MerchantProduct): void {
    if (this.saving() || this.loading() || this.productSaveError()?.conflict) return;
    const updates = this.productUpdates(product);
    if (!updates.length) return;

    const startingVersion = product.businessVersion;
    let latest = product;
    this.saving.set(true);
    this.productSaveError.set(null);
    this.saved.set(false);
    from(updates)
      .pipe(
        concatMap(({ area, value }) =>
          this.api.updateMerchantProduct(product.productId, area, {
            ...value,
            expectedVersion: latest.businessVersion,
          }),
        ),
        tap((updated) => {
          latest = updated;
          product.businessVersion = updated.businessVersion;
          this.products.update((items) => items.map((item) => (item.productId === updated.productId ? updated : item)));
        }),
        last(),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: (updated) => {
          this.draft = { ...updated };
          this.saved.set(true);
        },
        error: (error: unknown) =>
          this.productSaveError.set({
            partial: latest.businessVersion !== startingVersion,
            conflict: error instanceof HttpErrorResponse && error.status === 409,
          }),
      });
  }

  private productUpdates(product: MerchantProduct): ProductUpdate[] {
    const original = this.products().find((item) => item.productId === product.productId);
    if (!original) return [];

    const profile = {
      name: product.name.trim(),
      brief: product.brief.trim(),
      description: product.description.trim(),
      imageUrl: product.imageUrl.trim(),
      thumbnailUrl: product.thumbnailUrl.trim(),
    };
    const updates: ProductUpdate[] = [];
    if ((Object.keys(profile) as (keyof typeof profile)[]).some((field) => profile[field] !== original[field])) {
      updates.push({ area: 'profile', value: profile });
    }
    if (product.unitPrice !== original.unitPrice) {
      updates.push({ area: 'price', value: { unitPrice: product.unitPrice } });
    }
    if (product.lifecycleStatus !== original.lifecycleStatus) {
      updates.push({ area: 'status', value: { status: product.lifecycleStatus } });
    }
    return updates;
  }
}
