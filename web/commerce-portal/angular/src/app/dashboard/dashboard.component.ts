import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Product } from 'src/model/IProduct';
import { ApiService, CatalogStore, StockAvailability } from '../services/api.service';
import { forkJoin, Subscription } from 'rxjs';
import { SeoService } from '../services/seo.service';
import { I18nService } from '../services/i18n.service';

type CatalogSort = 'default' | 'price-asc' | 'price-desc' | 'rating';

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
  readonly size = 12;
  loading = true;
  searchFailed = false;
  merchantId: number | null = null;
  minPrice: number | null = null;
  maxPrice: number | null = null;
  searchQuery = '';
  sort: CatalogSort = 'default';
  selectedProduct?: Product;
  bigImage = '';
  showLoader = false;
  selectedQuantity = 1;
  stock?: StockAvailability;
  stockLoading = false;
  basketError = '';
  private stockRequest = 0;
  private searchRequest?: Subscription;

  get totalPages(): number {
    return Math.ceil(this.total / this.size);
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
      this.page = Math.max(0, Math.trunc(Number(params.get('page')) || 0));
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
    this.bigImage = this.selectedProduct.imageUrl;
    this.selectedQuantity = 1;
    this.stock = undefined;
    this.basketError = '';
    this.stockLoading = true;
    const productId = this.selectedProduct.id;
    const request = ++this.stockRequest;
    forkJoin({ product: this.api.productDetail(productId), stocks: this.api.productAvailability([productId]) })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ product, stocks }) => {
          if (request !== this.stockRequest) return;
          this.selectedProduct = product;
          this.bigImage = product.imageUrl;
          this.applyStock(request, stocks[productId]);
        },
        error: () => {
          if (request !== this.stockRequest) return;
          this.basketError = 'catalogInactive';
          this.applyStock(request, { productId, listed: false, available: 0 });
        },
      });
  }

  private applyStock(request: number, stock?: StockAvailability): void {
    if (request !== this.stockRequest) return;
    this.stock = stock;
    this.stockLoading = false;
    this.changeDetector.markForCheck();
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
    if (
      this.showLoader ||
      this.stockLoading ||
      (this.stock && (!this.stock.listed || this.stock.available < this.selectedQuantity))
    )
      return;
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
        this.basketError = err.status === 404 ? 'catalogInactive' : 'addBasketFailed';
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
