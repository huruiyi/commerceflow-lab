import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Product } from 'src/model/IProduct';
import { readPage } from '../list-pagination/page-query';
import { ApiService, CatalogStore, StockAvailability } from '../services/api.service';
import { catchError, EMPTY, map, of, Subscription, switchMap, timeout } from 'rxjs';
import { SeoService } from '../services/seo.service';
import { I18nService } from '../services/i18n.service';

type CatalogSort = 'default' | 'price-asc' | 'price-desc' | 'rating';
const PRODUCT_QUERY_TIMEOUT_MS = 8000;

@Component({
  standalone: false,
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css'],
})
export class DashboardComponent implements OnInit {
  products: Product[] = [];
  stores: CatalogStore[] = [];
  total = 0;
  page = 0;
  displayPage = 0;
  readonly size = 12;
  loading = true;
  searchFailed = false;
  merchantId: number | null = null;
  minPrice: number | null = null;
  maxPrice: number | null = null;
  searchQuery = '';
  sort: CatalogSort = 'default';
  selectedProduct?: Product;
  detailStatus: 'loading' | 'ready' | 'missing' | 'unavailable' = 'loading';
  bigImage = '';
  showLoader = false;
  selectedQuantity = 1;
  stock?: StockAvailability;
  stockLoading = false;
  basketError = '';
  private detailRequest?: Subscription;
  private searchRequest?: Subscription;

  get totalPages(): number {
    return Math.ceil(this.total / this.size);
  }

  get canAddToBasket(): boolean {
    return (
      this.detailStatus === 'ready' &&
      !this.showLoader &&
      !this.stockLoading &&
      this.stock !== undefined &&
      this.stock.available >= this.selectedQuantity
    );
  }

  constructor(
    private api: ApiService,
    private router: Router,
    private route: ActivatedRoute,
    private seoService: SeoService,
    private changeDetector: ChangeDetectorRef,
    public i18n: I18nService,
    private destroyRef: DestroyRef,
  ) {
    seoService.setTitle('Product List');
    seoService.setMeta('The page that lists products...');
  }

  ngOnInit(): void {
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.loadProducts());
    this.api
      .publicStores()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stores) => {
          this.stores = stores;
          this.changeDetector.markForCheck();
        },
        error: () => {
          this.stores = [];
        },
      });
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.searchQuery = (params.get('q') || '').trim();
      const sort = params.get('sort');
      this.sort = sort === 'price-asc' || sort === 'price-desc' || sort === 'rating' ? sort : 'default';
      this.merchantId = params.has('merchantId') ? Number(params.get('merchantId')) : null;
      this.minPrice = params.has('minPrice') ? Number(params.get('minPrice')) : null;
      this.maxPrice = params.has('maxPrice') ? Number(params.get('maxPrice')) : null;
      this.page = readPage(params.get('page'));
      this.loadProducts();
    });
  }

  clearSearch(): void {
    this.updateQuery({ q: null, page: null });
  }

  changeSort(sort: CatalogSort): void {
    this.updateQuery({ sort: sort === 'default' ? null : sort, page: null });
  }

  applyFilters(): void {
    this.updateQuery({ merchantId: this.merchantId, minPrice: this.minPrice, maxPrice: this.maxPrice, page: null });
  }

  changePage(page: number): void {
    this.updateQuery({ page: page || null });
  }

  private updateQuery(queryParams: Record<string, string | number | null>): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams, queryParamsHandling: 'merge' });
  }

  loadProducts(): void {
    this.searchRequest?.unsubscribe();
    const params: Record<string, string | number> = {
      q: this.searchQuery,
      sort: this.sort,
      page: this.page,
      size: this.size,
    };
    if (this.merchantId !== null) params['merchantId'] = this.merchantId;
    if (this.minPrice !== null) params['minPrice'] = this.minPrice;
    if (this.maxPrice !== null) params['maxPrice'] = this.maxPrice;
    this.loading = true;
    this.searchFailed = false;
    this.searchRequest = this.api
      .searchProducts(params)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (value) => {
          this.products = value.products;
          this.total = value.total;
          this.page = value.page;
          this.displayPage = value.page;
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

  fncSelectDetail(index: number) {
    this.selectedProduct = this.products[index];
    this.detailStatus = 'loading';
    this.selectedQuantity = 1;
    this.loadProductDetail();
  }

  loadProductDetail(): void {
    if (!this.selectedProduct) return;
    const productId = this.selectedProduct.id;
    this.detailRequest?.unsubscribe();
    if (this.detailStatus !== 'ready') this.detailStatus = 'loading';
    this.stock = undefined;
    this.basketError = '';
    this.stockLoading = true;
    this.detailRequest = this.api
      .productDetail(productId)
      .pipe(
        timeout(PRODUCT_QUERY_TIMEOUT_MS),
        catchError((error: unknown) => {
          this.detailStatus = error instanceof HttpErrorResponse && error.status === 404 ? 'missing' : 'unavailable';
          this.stockLoading = false;
          this.changeDetector.markForCheck();
          return EMPTY;
        }),
        switchMap((product) => {
          this.selectedProduct = product;
          this.bigImage = product.imageUrl;
          this.detailStatus = 'ready';
          this.changeDetector.markForCheck();
          return this.api.productAvailability([productId]).pipe(
            timeout(PRODUCT_QUERY_TIMEOUT_MS),
            map((stocks) => stocks[productId]),
            catchError(() => of(undefined)),
          );
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((stock) => {
        this.stock = stock?.listed ? stock : undefined;
        this.stockLoading = false;
        this.changeDetector.markForCheck();
      });
  }

  fncSelectBigImage(path: string) {
    this.bigImage = path;
  }

  changeSelectedQuantity(quantity: number) {
    this.selectedQuantity = this.normalizeQuantity(quantity);
  }

  quantityInput(event: Event) {
    this.changeSelectedQuantity((event.target as HTMLInputElement).valueAsNumber);
  }

  fncAddBasket(productId: number) {
    if (!this.canAddToBasket || this.selectedProduct?.id !== productId) return;
    this.basketError = '';
    this.showLoader = true;
    this.api.addOrder(productId, this.selectedQuantity).subscribe({
      next: () => {
        //try
        //console.log(res.order[0].durum);
        //newThis.router.navigate(['/order'])
        window.location.href = '/cart';
      },
      error: (err) => {
        this.basketError = err.status === 404 ? 'productMissingOrUnlisted' : 'addBasketFailed';
        this.showLoader = false;
        this.changeDetector.markForCheck();
      },
      complete: () => {
        this.showLoader = false;
        this.changeDetector.markForCheck();
      },
    });
  }

  private normalizeQuantity(quantity: number): number {
    if (!Number.isFinite(quantity)) {
      return 1;
    }
    return Math.min(99, Math.max(1, Math.trunc(quantity)));
  }
}
