import { Component, OnInit } from '@angular/core';
import {  Router } from '@angular/router';
import { ProBilgiler } from 'src/model/IProduct';
import { ApiService } from '../services/api.service';
import { SeoService } from '../services/seo.service';

@Component({
  standalone: false,
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrls: ['./dashboard.component.css']
})
export class DashboardComponent implements OnInit {
  proBilgiler :ProBilgiler[] = []
  singleBilgi:ProBilgiler = {}
  bigImage = ''
  showLoader = false

  constructor(private api :ApiService, private router: Router, private seoService: SeoService) {
    seoService.setTitle("Product List")
    seoService.setMeta("The page that lists products...")

    const newThis = this
    const allProductList = api.allProduct().subscribe({
      next(value) {
        newThis.proBilgiler = value.Products[0].bilgiler
      },
      error(err) {
        console.log(err);

      },
    })
  }

  ngOnInit(): void { }

  fncSelectDetail( index:number ) {
    this.singleBilgi = this.proBilgiler[index]
    this.bigImage = this.singleBilgi.images![0].normal
  }

  fncSelectBigImage( path:string ) {
    this.bigImage = path
  }

  fncAddBasket(productId :string ) {
    const newThis = this
    this.showLoader = true
    const basketObj = this.api.addOrder(productId)

    if (basketObj) {
      basketObj.subscribe({
        next(res) {
          //try
          //console.log(res.order[0].durum);
          //newThis.router.navigate(['/order'])
          window.location.href = '/order'
        },
        error(err) {
          //catch
          console.log(err);

        },
        complete() {
          //finally
          newThis.showLoader = false
          //console.log('Done!');
        },
      })
    }

  }

}
