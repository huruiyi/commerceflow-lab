import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, OnInit, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { concatMap, distinctUntilChanged, finalize, from, last, map, Subscription, tap } from 'rxjs';
import { readPage } from '../list-pagination/page-query';
import { ApiService, MerchantProduct } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

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
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private pageRequest?: Subscription;
  private original?: MerchantProduct;
  readonly i18n = inject(I18nService);
  readonly products = signal<MerchantProduct[]>([]);
  readonly page = signal(0);
  readonly total = signal(0);
  readonly totalPages = signal(0);
  readonly loading = signal(true);
  readonly loadFailed = signal(false);
  readonly saving = signal(false);
  readonly saveFailed = signal(false);
  readonly productSaveError = signal<{ partial: boolean; conflict: boolean } | null>(null);
  readonly productReloadFailed = signal(false);
  readonly saved = signal(false);
  draft?: MerchantProduct;
  creationOpen = false;
  creation = { name: '', description: '', unitPrice: 0.01, imageUrl: '' };
  private createRequest?: { key: string; content: string };

  ngOnInit(): void {
    this.route.queryParamMap
      .pipe(
        map((params) => readPage(params.get('page'))),
        distinctUntilChanged(),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((page) => this.load(page));
  }

  load(page = readPage(this.route.snapshot.queryParamMap.get('page'))): void {
    this.pageRequest?.unsubscribe();
    this.loading.set(true);
    this.loadFailed.set(false);
    this.pageRequest = this.api
      .merchantProductPage(page)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.loading.set(false)),
      )
      .subscribe({
        next: (result) => {
          this.products.set(result.rows);
          this.page.set(result.page);
          this.total.set(result.total);
          this.totalPages.set(Math.ceil(result.total / result.size));
          if (page !== result.page) this.changePage(result.page, true);
        },
        error: () => this.loadFailed.set(true),
      });
  }

  changePage(page: number, replaceUrl = false): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { page: page || null },
      queryParamsHandling: 'merge',
      replaceUrl,
    });
  }

  edit(product: MerchantProduct, focus = false): void {
    this.original = product;
    this.draft = { ...product };
    this.saveFailed.set(false);
    this.productSaveError.set(null);
    this.productReloadFailed.set(false);
    this.saved.set(false);
    if (focus) {
      afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('#merchant-edit-title')?.focus(), {
        injector: this.injector,
      });
    }
  }

  reloadProduct(productId: number): void {
    if (this.saving()) return;
    this.saving.set(true);
    this.productReloadFailed.set(false);
    this.api
      .merchantProduct(productId)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.saving.set(false)),
      )
      .subscribe({
        next: (product) => {
          this.products.update((items) => items.map((item) => (item.productId === productId ? product : item)));
          this.edit(product);
        },
        error: () => this.productReloadFailed.set(true),
      });
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
          this.creation = { name: '', description: '', unitPrice: 0.01, imageUrl: '' };
          this.createRequest = undefined;
          this.creationOpen = false;
          this.edit(product, true);
          this.load();
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
          this.original = updated;
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
    const original = this.original;
    if (!original || original.productId !== product.productId) return [];

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
