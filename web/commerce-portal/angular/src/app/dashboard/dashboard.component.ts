import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { Subscription, timeout } from 'rxjs';
import { Product } from 'src/model/IProduct';
import { ApiService, CatalogStore } from '../services/api.service';
import { I18nService } from '../services/i18n.service';
import { SeoService } from '../services/seo.service';
import {
  CatalogQuery,
  CatalogSort,
  catalogQueryParams,
  catalogSearchParams,
  catalogSortOptions,
  catalogMatchOptions,
  invalidCatalogKeyword,
  invalidCatalogPrice,
  invalidCatalogSku,
  readCatalogQuery,
} from './catalog-query';

type FilterField = 'q' | 'exclude' | 'merchantId' | 'minPrice' | 'maxPrice' | 'sku';

@Component({
  standalone: false,
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.css',
})
export class DashboardComponent implements OnInit {
  readonly size = 12;
  readonly sorts = catalogSortOptions;
  products: Product[] = [];
  stores: CatalogStore[] = [];
  storeNames = new Map<number, string>();
  total = 0;
  displayPage = 0;
  loading = true;
  searchFailed = false;
  mobileFiltersOpen = false;
  filters: CatalogQuery;
  draft: CatalogQuery;
  private searchRequest?: Subscription;

  constructor(
    private readonly api: ApiService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    private readonly seo: SeoService,
    private readonly changeDetector: ChangeDetectorRef,
    readonly i18n: I18nService,
    private readonly destroyRef: DestroyRef,
  ) {
    this.filters = readCatalogQuery(this.route.snapshot.queryParamMap);
    this.draft = { ...this.filters };
  }

  get totalPages(): number {
    return Math.ceil(this.total / this.size);
  }

  get detailQueryParams(): Params {
    return catalogQueryParams({ ...this.filters, page: this.displayPage });
  }

  get hasFilters(): boolean {
    const { q, sku, merchantId, minPrice, maxPrice, sort } = this.filters;
    return (
      Boolean(q || sku || this.filters.exclude) ||
      merchantId !== null ||
      minPrice !== null ||
      maxPrice !== null ||
      sort !== 'default'
    );
  }

  get appliedFilters(): { field: FilterField; text: string }[] {
    const { q, sku, merchantId, minPrice, maxPrice } = this.filters;
    const applied: { field: FilterField; text: string }[] = [];
    if (q) {
      const mode = this.matchOptions.find((option) => option.value === this.filters.match)!;
      applied.push({ field: 'q', text: mode.value === 'all' ? q : `${this.i18n.t(mode.label)} · ${q}` });
    }
    if (this.filters.exclude) {
      applied.push({ field: 'exclude', text: `${this.i18n.t('catalogExclude')} · ${this.filters.exclude}` });
    }
    if (sku) applied.push({ field: 'sku', text: sku });
    if (merchantId !== null) {
      const text = this.storeNames.get(merchantId) ?? this.i18n.t('catalogStoreNumber', { id: merchantId });
      applied.push({ field: 'merchantId', text });
    }
    const price = new Intl.NumberFormat(this.i18n.locale(), { style: 'currency', currency: 'CNY' });
    if (minPrice !== null) {
      applied.push({
        field: 'minPrice',
        text: this.i18n.t('catalogMinPriceFilter', { price: price.format(minPrice) }),
      });
    }
    if (maxPrice !== null) {
      applied.push({
        field: 'maxPrice',
        text: this.i18n.t('catalogMaxPriceFilter', { price: price.format(maxPrice) }),
      });
    }
    return applied;
  }

  get invalidPrice(): boolean {
    return invalidCatalogPrice(this.draft);
  }

  get invalidKeyword(): boolean {
    return invalidCatalogKeyword(this.draft.q);
  }

  readonly matchOptions = catalogMatchOptions;

  get invalidExclude(): boolean {
    return invalidCatalogKeyword(this.draft.exclude);
  }

  get invalidSku(): boolean {
    return invalidCatalogSku(this.draft.sku);
  }

  ngOnInit(): void {
    this.updateTitle();
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => {
      this.updateTitle();
      this.loadProducts();
    });
    this.api
      .publicStores()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stores) => {
          this.stores = stores;
          this.storeNames = new Map(stores.map((store) => [store.merchantId, store.name]));
          this.changeDetector.markForCheck();
        },
        error: () => {
          this.stores = [];
        },
      });
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.filters = readCatalogQuery(params);
      this.draft = { ...this.filters };
      if (this.invalidPrice || this.invalidSku || this.invalidExclude) this.mobileFiltersOpen = true;
      this.loadProducts();
    });
  }

  applyFilters(): void {
    if (this.invalidPrice || this.invalidKeyword || this.invalidExclude || this.invalidSku) return;
    this.updateQuery({ ...this.draft, q: this.draft.q.trim(), sku: this.draft.sku?.trim(), page: 0 });
  }

  clearFilters(): void {
    this.draft = {
      q: '',
      match: 'all',
      exclude: '',
      merchantId: null,
      minPrice: null,
      maxPrice: null,
      sort: 'default',
      page: 0,
    };
    this.mobileFiltersOpen = false;
    this.updateQuery(this.draft);
  }

  removeFilter(field: FilterField): void {
    this.updateQuery({
      ...this.filters,
      [field]: field === 'q' || field === 'sku' || field === 'exclude' ? '' : null,
      page: 0,
    });
  }

  changeSort(sort: CatalogSort): void {
    this.updateQuery({ ...this.filters, sort, page: 0 });
  }

  changePage(page: number): void {
    this.updateQuery({ ...this.filters, page });
  }

  loadProducts(): void {
    this.searchRequest?.unsubscribe();
    if (
      invalidCatalogPrice(this.filters) ||
      invalidCatalogKeyword(this.filters.q) ||
      invalidCatalogKeyword(this.filters.exclude) ||
      invalidCatalogSku(this.filters.sku)
    ) {
      this.products = [];
      this.total = 0;
      this.loading = false;
      this.searchFailed = false;
      this.changeDetector.markForCheck();
      return;
    }
    const params = catalogSearchParams(this.filters, this.size);
    this.loading = true;
    this.searchFailed = false;
    this.searchRequest = this.api
      .searchProducts(params)
      .pipe(timeout(8000), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ products, total, page: effectivePage }) => {
          this.products = products;
          this.total = total;
          this.displayPage = effectivePage;
          this.loading = false;
          this.changeDetector.markForCheck();
        },
        error: () => {
          this.products = [];
          this.total = 0;
          this.loading = false;
          this.searchFailed = true;
          this.changeDetector.markForCheck();
        },
      });
  }

  private updateQuery(query: CatalogQuery): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams: catalogQueryParams(query) });
  }

  private updateTitle(): void {
    this.seo.setTitle(`${this.i18n.t('catalogBrowse')} | CommerceFlow Lab`);
    this.seo.setMeta(this.i18n.t('searchProducts'));
  }
}
