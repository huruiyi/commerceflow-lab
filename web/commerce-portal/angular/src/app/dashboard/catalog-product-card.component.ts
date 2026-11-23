import { DecimalPipe } from '@angular/common';
import { Component, computed, inject, input } from '@angular/core';
import { Params, RouterLink } from '@angular/router';
import { Product } from 'src/model/IProduct';
import { I18nService } from '../services/i18n.service';
import { CatalogImageComponent } from './catalog-image.component';

@Component({
  selector: 'app-catalog-product-card',
  imports: [CatalogImageComponent, DecimalPipe, RouterLink],
  template: `
    <article>
      <a class="product-card" [routerLink]="['/products', item().id]" [queryParams]="queryParams()">
        <app-catalog-image [primary]="item().thumbnailUrl" [secondary]="item().imageUrl" />
        <div class="card-body">
          @if (storeName()) {
            <p class="store-name">{{ storeName() }}</p>
          }
          <h2>{{ item().name }}</h2>
          @if (item().brief) {
            <p class="brief">{{ item().brief }}</p>
          }
          <div class="card-facts">
            <strong class="price">{{ price() }}</strong>
            @if (item().reviewCount) {
              <span class="rating">
                <i class="bi bi-star-fill" aria-hidden="true"></i>
                {{ item().averageRating | number: '1.1-1' }}
                <span>{{ i18n.t('catalogReviewCount', { count: item().reviewCount }) }}</span>
              </span>
            }
          </div>
        </div>
      </a>
    </article>
  `,
  styles: `
    :host,
    article {
      display: block;
      height: 100%;
      min-width: 0;
    }

    .product-card {
      display: flex;
      height: 100%;
      flex-direction: column;
      overflow: hidden;
      border: 1px solid #dce5df;
      border-radius: 0.75rem;
      background: #fff;
      color: #172723;
      text-decoration: none;
      transition:
        border-color 150ms ease,
        box-shadow 150ms ease;
    }

    .product-card:hover {
      border-color: #96afa1;
      box-shadow: 0 4px 14px rgb(23 39 35 / 8%);
    }

    .product-card:focus-visible {
      outline: 2px solid #17664d;
      outline-offset: 3px;
    }

    app-catalog-image {
      aspect-ratio: 4 / 3;
    }

    .card-body {
      display: flex;
      flex: 1;
      flex-direction: column;
      min-width: 0;
      padding: 0.85rem 0.95rem 0.95rem;
    }

    h2 {
      margin: 0 0 0.35rem;
      font-size: 1rem;
      font-weight: 650;
      line-height: 1.35;
      overflow-wrap: anywhere;
    }

    .store-name,
    .brief {
      margin: 0 0 0.4rem;
      color: #52615b;
      font-size: 0.8125rem;
      overflow-wrap: anywhere;
    }

    .brief {
      display: -webkit-box;
      overflow: hidden;
      -webkit-box-orient: vertical;
      -webkit-line-clamp: 2;
    }

    .card-facts {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 0.25rem 0.5rem;
      margin-top: auto;
      padding-top: 0.45rem;
    }

    .price {
      color: #17664d;
      font-size: 1.125rem;
      font-variant-numeric: tabular-nums;
    }

    .rating {
      display: inline-flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 0.25rem;
      color: #52615b;
      font-size: 0.75rem;
    }

    .rating i {
      color: #9a5907;
    }
  `,
})
export class CatalogProductCardComponent {
  readonly i18n = inject(I18nService);
  readonly item = input.required<Product>();
  readonly storeName = input('');
  readonly queryParams = input<Params>({});
  protected readonly price = computed(() =>
    new Intl.NumberFormat(this.i18n.locale(), { style: 'currency', currency: 'CNY' }).format(this.item().price),
  );
}
