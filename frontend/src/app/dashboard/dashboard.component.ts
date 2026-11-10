import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import {  Router } from '@angular/router';
import { Product } from 'src/model/IProduct';
import { ApiService } from '../services/api.service';
import { SeoService } from '../services/seo.service';

@Component({
  standalone: false,
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit {
  products: Product[] = []
  selectedProduct?: Product
  bigImage = ''
  showLoader = false
  selectedQuantity = 1

  constructor(private api: ApiService, private router: Router, private seoService: SeoService,
              private changeDetector: ChangeDetectorRef) {
    seoService.setTitle("Product List")
    seoService.setMeta("The page that lists products...")

    api.allProducts().subscribe({
      next: value => {
        this.products = value
        this.changeDetector.markForCheck()
      },
      error: err => {
        console.log(err)
      },
    })
  }

  ngOnInit(): void { }

  fncSelectDetail( index:number ) {
    this.selectedProduct = this.products[index]
    this.bigImage = this.selectedProduct.imageUrl
    this.selectedQuantity = 1
  }

  fncSelectBigImage( path:string ) {
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
          window.location.href = '/order'
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
