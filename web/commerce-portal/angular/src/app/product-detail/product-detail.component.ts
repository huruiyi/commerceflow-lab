import { ViewportScroller } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { catchError, distinctUntilChanged, EMPTY, map, of, Subscription, switchMap, timeout } from 'rxjs';
import { CatalogSku } from 'src/model/ICatalogSku';
import { Product, ProductReview } from 'src/model/IProduct';
import { catalogQueryParams, readCatalogQuery, readPositiveId } from '../dashboard/catalog-query';
import { readPage } from '../list-pagination/page-query';
import { AccountService } from '../services/account.service';
import { ApiService, StockAvailability } from '../services/api.service';
import { I18nService } from '../services/i18n.service';
import { SeoService } from '../services/seo.service';

const PRODUCT_QUERY_TIMEOUT_MS = 8000;
const REVIEW_PAGE_SIZE = 5;

@Component({
  standalone: false,
  selector: 'app-product-detail',
  templateUrl: './product-detail.component.html',
  styleUrl: './product-detail.component.css',
})
export class ProductDetailComponent implements OnInit {
  product?: Product;
  detailStatus: 'loading' | 'ready' | 'missing' | 'unavailable' = 'loading';
  reviews: ProductReview[] = [];
  reviewPage = 0;
  reviewTotal = 0;
  loadingReviews = false;
  failedReviewPage?: number;
  selectedQuantity = 1;
  selectedSku?: CatalogSku;
  stock?: StockAvailability;
  stockLoading = false;
  basketLoading = false;
  basketError = '';
  private productId: number | null = null;
  private detailRequest?: Subscription;
  private skuStockRequest?: Subscription;
  private reviewRequest?: Subscription;
  private basketRequest?: Subscription;

  constructor(
    private readonly api: ApiService,
    private readonly router: Router,
    private readonly route: ActivatedRoute,
    private readonly seo: SeoService,
    private readonly viewport: ViewportScroller,
    private readonly changeDetector: ChangeDetectorRef,
    readonly account: AccountService,
    readonly i18n: I18nService,
    private readonly destroyRef: DestroyRef,
  ) {}

  get reviewPages(): number {
    return Math.ceil(this.reviewTotal / REVIEW_PAGE_SIZE);
  }

  get isCustomer(): boolean {
    return this.account.userProfile()?.role === 'CUSTOMER';
  }

  get returnStore(): number | null {
    return readPositiveId(this.route.snapshot.queryParamMap.get('storeId'));
  }

  get returnQueryParams(): Params {
    const params = this.route.snapshot.queryParamMap;
    const query = readCatalogQuery(params);
    return this.returnStore
      ? catalogQueryParams({ ...query, merchantId: null, page: readPage(params.get('storePage')) })
      : catalogQueryParams(query);
  }

  get signInQueryParams(): Params {
    return { returnUrl: this.router.url };
  }

  get unitPrice(): number {
    return this.selectedSku?.unitPrice ?? this.product?.price ?? 0;
  }

  get canAddToBasket(): boolean {
    return (
      this.isCustomer &&
      this.detailStatus === 'ready' &&
      !this.basketLoading &&
      !this.stockLoading &&
      this.stock !== undefined &&
      this.stock.available >= this.selectedQuantity
    );
  }

  ngOnInit(): void {
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.loadProductDetail());
    this.route.paramMap
      .pipe(
        map((params) => readPositiveId(params.get('id'))),
        distinctUntilChanged(),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((id) => {
        this.productId = id;
        this.product = undefined;
        this.selectedSku = undefined;
        this.selectedQuantity = 1;
        this.basketRequest?.unsubscribe();
        this.basketLoading = false;
        this.viewport.scrollToPosition([0, 0]);
        this.loadProductDetail();
      });
  }

  loadProductDetail(): void {
    this.detailRequest?.unsubscribe();
    this.skuStockRequest?.unsubscribe();
    this.reviewRequest?.unsubscribe();
    const id = this.productId;
    this.loadingReviews = false;
    this.failedReviewPage = undefined;
    this.stock = undefined;
    this.basketError = '';
    const authenticated = this.account.userProfile() !== null;
    this.stockLoading = authenticated;
    this.setPageTitle(this.product);
    if (id === null) {
      this.detailStatus = 'missing';
      this.stockLoading = false;
      this.changeDetector.markForCheck();
      return;
    }

    this.detailStatus = this.product ? 'ready' : 'loading';
    this.detailRequest = this.api
      .productDetail(id)
      .pipe(
        timeout(PRODUCT_QUERY_TIMEOUT_MS),
        catchError((error: unknown) => {
          this.product = undefined;
          this.detailStatus = error instanceof HttpErrorResponse && error.status === 404 ? 'missing' : 'unavailable';
          this.stockLoading = false;
          this.setPageTitle();
          this.changeDetector.markForCheck();
          return EMPTY;
        }),
        switchMap((product) => {
          this.product = product;
          const options = product.skus ?? [];
          this.selectedSku =
            options.length > 1
              ? (options.find((sku) => sku.skuId === this.selectedSku?.skuId) ?? options[0])
              : undefined;
          this.reviews = product.reviews.slice(0, REVIEW_PAGE_SIZE);
          this.reviewPage = 0;
          this.reviewTotal = product.reviewCount;
          this.detailStatus = 'ready';
          this.setPageTitle(product);
          this.changeDetector.markForCheck();
          if (!authenticated) return of(undefined);
          return this.availability(this.selectedSku?.stockProductId ?? id);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((stock) => this.updateStock(stock));
  }

  skuLabel(sku: CatalogSku): string {
    return sku.defaultSku
      ? this.i18n.t('catalogSkuDefaultOption')
      : Object.entries(sku.specifications)
          .map(([key, value]) => key + ': ' + value)
          .join(' / ');
  }

  selectSku(skuId: number): void {
    const sku = this.product?.skus?.find((option) => option.skuId === skuId);
    if (!sku || sku === this.selectedSku) return;
    this.selectedSku = sku;
    this.selectedQuantity = 1;
    this.basketError = '';
    if (!this.account.userProfile() || sku.stockProductId === null) return;

    this.detailRequest?.unsubscribe();
    this.skuStockRequest?.unsubscribe();
    this.stock = undefined;
    this.stockLoading = true;
    this.skuStockRequest = this.availability(sku.stockProductId)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((stock) => this.updateStock(stock));
  }

  loadReviews(page: number): void {
    if (this.detailStatus !== 'ready' || !this.product) return;
    this.reviewRequest?.unsubscribe();
    this.failedReviewPage = undefined;
    const { reviews, reviewCount } = this.product;
    if (reviews.length === reviewCount && reviewCount > REVIEW_PAGE_SIZE) {
      const start = page * REVIEW_PAGE_SIZE;
      this.reviews = reviews.slice(start, start + REVIEW_PAGE_SIZE);
      this.reviewPage = page;
      this.changeDetector.markForCheck();
      return;
    }
    this.loadingReviews = true;
    this.reviewRequest = this.api
      .productReviews(this.product.id, page, REVIEW_PAGE_SIZE)
      .pipe(timeout(PRODUCT_QUERY_TIMEOUT_MS), takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          this.reviews = result.reviews;
          this.reviewPage = result.page;
          this.reviewTotal = result.total;
          this.loadingReviews = false;
          this.changeDetector.markForCheck();
        },
        error: () => {
          this.failedReviewPage = page;
          this.loadingReviews = false;
          this.changeDetector.markForCheck();
        },
      });
  }

  changeQuantity(quantity: number): void {
    this.selectedQuantity = Number.isFinite(quantity) ? Math.min(99, Math.max(1, Math.trunc(quantity))) : 1;
  }

  readQuantity(event: Event): void {
    const input = event.currentTarget as HTMLInputElement;
    this.changeQuantity(input.valueAsNumber);
    input.value = String(this.selectedQuantity);
  }

  addToBasket(): void {
    if (!this.canAddToBasket || !this.product) return;
    this.basketError = '';
    this.basketLoading = true;
    const request = this.selectedSku
      ? this.api.addOrder(this.product.id, this.selectedQuantity, this.selectedSku.skuId)
      : this.api.addOrder(this.product.id, this.selectedQuantity);
    this.basketRequest = request
      .pipe(
        switchMap(() => this.router.navigate(['/cart'])),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        error: (error: HttpErrorResponse) => {
          this.basketError = error.status === 404 ? 'productMissingOrUnlisted' : 'addBasketFailed';
          this.basketLoading = false;
          this.changeDetector.markForCheck();
        },
        complete: () => {
          this.basketLoading = false;
          this.changeDetector.markForCheck();
        },
      });
  }

  private availability(stockProductId: number) {
    return this.api.productAvailability([stockProductId]).pipe(
      timeout(PRODUCT_QUERY_TIMEOUT_MS),
      map((stocks) => stocks[stockProductId]),
      catchError(() => of(undefined)),
    );
  }

  private updateStock(stock?: StockAvailability): void {
    this.stock = stock?.listed ? stock : undefined;
    this.stockLoading = false;
    this.changeDetector.markForCheck();
  }

  private setPageTitle(product?: Product): void {
    this.seo.setTitle(`${product?.name || this.i18n.t('details')} | CommerceFlow Lab`);
    this.seo.setMeta(product?.brief || this.i18n.t('details'));
  }
}
