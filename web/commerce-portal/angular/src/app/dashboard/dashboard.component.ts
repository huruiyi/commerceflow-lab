import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { Product } from 'src/model/IProduct';
import { ApiService, StockAvailability } from '../services/api.service';
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
  allProducts: Product[] = [];
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
    this.loadProducts();
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.loadProducts());
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      this.searchQuery = (params.get('q') || '').trim();
      const sort = params.get('sort');
      this.sort = sort === 'price-asc' || sort === 'price-desc' || sort === 'rating' ? sort : 'default';
      this.applySearch();
      this.changeDetector.markForCheck();
    });
  }

  clearSearch(): void {
    this.updateQuery({ q: null });
  }

  changeSort(sort: CatalogSort): void {
    this.updateQuery({ sort: sort === 'default' ? null : sort });
  }

  private updateQuery(queryParams: { q?: null; sort?: string | null }): void {
    void this.router.navigate([], { relativeTo: this.route, queryParams, queryParamsHandling: 'merge' });
  }

  private loadProducts(): void {
    this.api.allProducts().subscribe({
      next: (value) => {
        this.allProducts = value;
        this.applySearch();
        this.changeDetector.markForCheck();
      },
      error: (err) => console.log(err),
    });
  }

  private applySearch(): void {
    const query = this.searchQuery.toLocaleLowerCase();
    this.products = query
      ? this.allProducts.filter((product) =>
          [product.name, product.brief, product.description].some((value) => value.toLocaleLowerCase().includes(query)),
        )
      : [...this.allProducts];
    switch (this.sort) {
      case 'price-asc':
        this.products.sort((a, b) => a.price - b.price);
        break;
      case 'price-desc':
        this.products.sort((a, b) => b.price - a.price);
        break;
      case 'rating':
        this.products.sort((a, b) => b.averageRating - a.averageRating);
        break;
    }
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
    this.api
      .productAvailability([productId])
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (stocks) => this.applyStock(request, stocks[productId]),
        error: () => this.applyStock(request),
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
