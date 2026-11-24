import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, NavigationEnd, Params, Router, RouterLink } from '@angular/router';
import {
  catchError,
  distinctUntilChanged,
  EMPTY,
  filter,
  finalize,
  map,
  merge,
  Observable,
  startWith,
  Subject,
  switchMap,
} from 'rxjs';
import { CatalogProductCardComponent } from '../dashboard/catalog-product-card.component';
import { CatalogSkuFacetsComponent } from '../dashboard/catalog-sku-facets.component';
import { CatalogSearchFormComponent } from '../dashboard/catalog-search-form.component';
import {
  CatalogQuery,
  CatalogSort,
  catalogQueryParams,
  catalogSearchParams,
  catalogSortOptions,
  invalidCatalogKeyword,
  invalidCatalogPrice,
  invalidCatalogSku,
  readCatalogQuery,
} from '../dashboard/catalog-query';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { ApiService, CatalogSearchPage } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

interface Store {
  merchantId: number;
  name: string;
  description: string;
  status: string;
}

@Component({
  selector: 'app-stores',
  imports: [
    CommonModule,
    FormsModule,
    RouterLink,
    ListPaginationComponent,
    CatalogProductCardComponent,
    CatalogSkuFacetsComponent,
    CatalogSearchFormComponent,
  ],
  templateUrl: './stores.component.html',
  styleUrl: './stores.component.css',
})
export class StoresComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ApiService);
  private readonly destroy = inject(DestroyRef);
  readonly i18n = inject(I18nService);
  readonly mode = this.route.snapshot.data['mode'];
  readonly store = signal<Store | null>(null);
  readonly stores = signal<Store[]>([]);
  readonly storeStatuses = ['PENDING', 'ACTIVE', 'REJECTED', 'DISABLED'];
  readonly storeQuery = signal('');
  readonly storeStatus = signal('');
  readonly storeListLoading = signal(false);
  readonly storeListFailed = signal(false);
  readonly matchingStores = computed(() => {
    const query = this.storeQuery().trim().toLocaleLowerCase();
    const merchantId = query.replace(/^#/, '');
    const status = this.storeStatus();
    return this.stores().filter(
      (store) =>
        (!status || store.status === status) &&
        (!query || store.name.toLocaleLowerCase().includes(query) || String(store.merchantId) === merchantId),
    );
  });
  private readonly storePageSize = 10;
  private readonly requestedStorePage = signal(0);
  readonly storePageCount = computed(() => Math.ceil(this.matchingStores().length / this.storePageSize));
  readonly storePage = computed(() => Math.min(this.requestedStorePage(), Math.max(0, this.storePageCount() - 1)));
  readonly pageStores = computed(() => {
    const start = this.storePage() * this.storePageSize;
    return this.matchingStores().slice(start, start + this.storePageSize);
  });
  readonly productPage = signal<CatalogSearchPage | null>(null);
  readonly totalPages = computed(() => {
    const page = this.productPage();
    return page ? Math.ceil(page.total / page.size) : 0;
  });
  readonly productsLoading = signal(false);
  readonly productsFailed = signal(false);
  private readonly pageSize = 12;
  private readonly productRetry = new Subject<void>();
  private productAttempt = 0;
  private catalog = readCatalogQuery(this.route.snapshot.queryParamMap);
  catalogDraft = { ...this.catalog };
  readonly catalogSorts = catalogSortOptions;
  readonly busy = signal(false);
  readonly message = signal('');
  readonly actionFailed = signal(false);
  createOpen = false;
  name = '';
  description = '';
  account = { email: '', password: '', name: '' };

  constructor() {
    if (this.mode === 'admin') this.reload();
    else if (this.mode === 'merchant') {
      this.loadStore();
    } else {
      this.route.paramMap
        .pipe(
          map((params) => Number(params.get('id'))),
          distinctUntilChanged(),
          switchMap((id) => {
            this.store.set(null);
            this.productPage.set(null);
            this.message.set('');
            return this.http.get<Store>(`/api/stores/${id}`).pipe(
              catchError((error) => {
                this.message.set(error.error?.message || '操作失败 / Request failed');
                return EMPTY;
              }),
            );
          }),
          takeUntilDestroyed(),
        )
        .subscribe((value) => this.store.set(value));
      merge(
        this.router.events.pipe(filter((event) => event instanceof NavigationEnd)),
        this.i18n.localeChanged,
        this.productRetry,
      )
        .pipe(
          startWith(null),
          map(() => ({
            merchantId: Number(this.route.snapshot.paramMap.get('id')),
            query: readCatalogQuery(this.route.snapshot.queryParamMap),
            locale: this.i18n.locale(),
            attempt: this.productAttempt,
          })),
          distinctUntilChanged(
            (a, b) =>
              a.merchantId === b.merchantId &&
              a.locale === b.locale &&
              a.attempt === b.attempt &&
              JSON.stringify(a.query) === JSON.stringify(b.query),
          ),
          switchMap(({ merchantId, query }) => {
            if (JSON.stringify(this.catalog) !== JSON.stringify(query)) this.catalogDraft = { ...query };
            this.catalog = query;
            if (
              invalidCatalogPrice(query) ||
              invalidCatalogKeyword(query.q) ||
              invalidCatalogKeyword(query.exclude) ||
              invalidCatalogSku(query.sku)
            ) {
              this.productPage.set(null);
              this.productsFailed.set(false);
              return EMPTY;
            }
            this.productsLoading.set(true);
            this.productsFailed.set(false);
            return this.api.searchProducts(catalogSearchParams({ ...query, merchantId }, this.pageSize)).pipe(
              catchError(() => {
                this.productPage.set(null);
                this.productsFailed.set(true);
                return EMPTY;
              }),
              finalize(() => this.productsLoading.set(false)),
            );
          }),
          takeUntilDestroyed(),
        )
        .subscribe((value) => this.productPage.set(value));
    }
  }

  filterStores(query: string, status: string): void {
    this.storeQuery.set(query);
    this.storeStatus.set(status);
    this.requestedStorePage.set(0);
  }

  changeStorePage(page: number): void {
    this.requestedStorePage.set(page);
  }

  get selectedSort(): CatalogSort {
    return this.catalog.sort;
  }

  changeSort(sort: CatalogSort): void {
    this.navigateCatalog({ ...this.catalog, sort, page: 0 });
  }

  get selectedSku(): string | undefined {
    return this.catalog.sku;
  }

  changeSku(sku: string): void {
    this.navigateCatalog({ ...this.catalog, sku, page: 0 });
  }

  changePage(page: number): void {
    this.navigateCatalog({ ...this.catalog, page });
  }

  get invalidPrice(): boolean {
    return invalidCatalogPrice(this.catalogDraft);
  }

  get invalidKeyword(): boolean {
    return invalidCatalogKeyword(this.catalogDraft.q);
  }

  get invalidExclude(): boolean {
    return invalidCatalogKeyword(this.catalogDraft.exclude);
  }

  get invalidSku(): boolean {
    return invalidCatalogSku(this.catalogDraft.sku);
  }

  applyCatalog(): void {
    if (this.invalidPrice || this.invalidKeyword || this.invalidExclude || this.invalidSku) return;
    this.navigateCatalog({
      ...this.catalogDraft,
      q: this.catalogDraft.q.trim(),
      sku: this.catalogDraft.sku?.trim(),
      page: 0,
    });
  }

  resetCatalog(): void {
    this.catalogDraft = {
      q: '',
      match: 'all',
      exclude: '',
      merchantId: null,
      minPrice: null,
      maxPrice: null,
      sort: 'default',
      page: 0,
    };
    this.navigateCatalog(this.catalogDraft);
  }

  reloadProducts(): void {
    this.productAttempt++;
    this.productRetry.next();
  }

  productBackParams(storeId: number, page: number): Params {
    return {
      ...catalogQueryParams({ ...this.catalog, merchantId: null, page: 0 }),
      storeId,
      storePage: page || null,
    };
  }

  private navigateCatalog(query: CatalogQuery): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: catalogQueryParams({ ...query, merchantId: null }),
    });
  }

  loadStore(): void {
    this.run(this.http.get<Store>('/api/merchant/store'), (value) => this.select(value));
  }

  save(): void {
    this.run(
      this.http.put<Store>('/api/merchant/store', { name: this.name, description: this.description }),
      (value) => {
        this.select(value);
        this.message.set('storeMerchantSaved');
      },
    );
  }

  createAccount(): void {
    this.run(this.http.post('/api/users/merchants', this.account), () => {
      this.message.set('storeAdminAccountCreated');
      this.account = { email: '', password: '', name: '' };
    });
  }

  toggleAccountForm(): void {
    this.createOpen = !this.createOpen;
    if (!this.createOpen) this.account.password = '';
  }

  decide(value: Store, status: string): void {
    this.run(this.http.put<Store>(`/api/admin/stores/${value.merchantId}/status`, { status }), (updated) =>
      this.stores.update((items) => items.map((item) => (item.merchantId === updated.merchantId ? updated : item))),
    );
  }

  reload(): void {
    if (this.storeListLoading()) return;
    this.storeListLoading.set(true);
    this.storeListFailed.set(false);
    this.http
      .get<Store[]>('/api/admin/stores')
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.storeListLoading.set(false)),
      )
      .subscribe({
        next: (values) => this.stores.set(values),
        error: () => this.storeListFailed.set(true),
      });
  }

  private select(store: Store | null): void {
    this.store.set(store);
    this.name = store?.name ?? '';
    this.description = store?.description ?? '';
  }

  private run<T>(request: Observable<T>, next: (value: T) => void): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.message.set('');
    this.actionFailed.set(false);
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next,
        error: (error) => {
          this.actionFailed.set(true);
          this.message.set(error.error?.message || 'requestFailed');
        },
      });
  }
}
