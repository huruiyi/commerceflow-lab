import {ChangeDetectorRef, Component, DestroyRef, OnInit} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {ActivatedRoute, Router} from '@angular/router';
import {Product} from 'src/model/IProduct';
import {ApiService} from '../services/api.service';
import {SeoService} from '../services/seo.service';
import {I18nService} from '../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit {
  products: Product[] = []
  allProducts: Product[] = []
  searchQuery = ''
  selectedProduct?: Product
  bigImage = ''
  showLoader = false
  selectedQuantity = 1

  constructor(private api: ApiService, private router: Router, private route: ActivatedRoute, private seoService: SeoService,
              private changeDetector: ChangeDetectorRef, public i18n: I18nService, private destroyRef: DestroyRef) {
    seoService.setTitle("Product List")
    seoService.setMeta("The page that lists products...")
  }

  ngOnInit(): void {
    this.loadProducts()
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.loadProducts())
    this.route.queryParamMap.subscribe(params => {
      this.searchQuery = (params.get('q') || '').trim()
      this.applySearch()
      this.changeDetector.markForCheck()
    })
  }

  clearSearch(): void {
    void this.router.navigate(['/dashboard'])
  }

  private loadProducts(): void {
    this.api.allProducts().subscribe({
      next: value => {
        this.allProducts = value
        this.applySearch()
        this.changeDetector.markForCheck()
      },
      error: err => console.log(err),
    })
  }

  private applySearch(): void {
    const query = this.searchQuery.toLocaleLowerCase()
    this.products = query ? this.allProducts.filter(product =>
      [product.name, product.brief, product.description].some(value => value.toLocaleLowerCase().includes(query))) : [...this.allProducts]
  }

  fncSelectDetail(index: number) {
    this.selectedProduct = this.products[index]
    this.bigImage = this.selectedProduct.imageUrl
    this.selectedQuantity = 1
  }

  fncSelectBigImage(path: string) {
    this.bigImage = path
  }

  changeSelectedQuantity(quantity: number) {
    this.selectedQuantity = this.normalizeQuantity(quantity)
  }

  quantityInput(event: Event) {
    this.changeSelectedQuantity((event.target as HTMLInputElement).valueAsNumber)
  }

  fncAddBasket(productId: number) {
    this.showLoader = true
    this.api.addOrder(productId, this.selectedQuantity).subscribe({
      next: () => {
        //try
        //console.log(res.order[0].durum);
        //newThis.router.navigate(['/order'])
        window.location.href = '/cart'
      },
      error: err => {
        console.log(err)
        this.showLoader = false
        this.changeDetector.markForCheck()
      },
      complete: () => {
        this.showLoader = false
        this.changeDetector.markForCheck()
      },
    })

  }

  private normalizeQuantity(quantity: number): number {
    if (!Number.isFinite(quantity)) {
      return 1
    }
    return Math.min(99, Math.max(1, Math.trunc(quantity)))
  }

}
