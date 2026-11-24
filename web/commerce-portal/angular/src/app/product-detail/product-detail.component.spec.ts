import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router, RouterModule } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { NEVER, of, Subject, throwError } from 'rxjs';
import { Product, ProductReview } from 'src/model/IProduct';
import { CatalogImageComponent } from '../dashboard/catalog-image.component';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { TranslatePipe } from '../pipes/translate.pipe';
import { TrcurrencyPipe } from '../pipes/trcurrency.pipe';
import { AccountService } from '../services/account.service';
import { ApiService, StockAvailability } from '../services/api.service';
import { ProductDetailComponent } from './product-detail.component';

describe('ProductDetailComponent', () => {
  let harness: RouterTestingHarness;
  let detail: jasmine.Spy;
  let availability: jasmine.Spy;
  let reviews: jasmine.Spy;
  let addBasket: jasmine.Spy;
  const profile = signal<{ role: string } | null>(null);
  const review: ProductReview = {
    id: 17,
    rating: 5,
    reviewerName: 'Demo student',
    comment: 'Works well',
    createdAt: '2026-11-24T12:00:00Z',
  };
  const product: Product = {
    id: 1,
    merchantId: 4,
    name: 'Product',
    brief: 'Short summary',
    description: 'Authoritative details',
    price: 10,
    imageUrl: '',
    thumbnailUrl: '',
    averageRating: 5,
    reviewCount: 7,
    reviews: [review],
  };

  beforeEach(async () => {
    profile.set({ role: 'CUSTOMER' });
    detail = jasmine.createSpy('productDetail').and.returnValue(of(product));
    availability = jasmine
      .createSpy('productAvailability')
      .and.returnValue(of({ 1: { productId: 1, listed: true, available: 4 } }));
    reviews = jasmine.createSpy('productReviews');
    addBasket = jasmine.createSpy('addOrder');
    await TestBed.configureTestingModule({
      imports: [RouterModule, CatalogImageComponent, ListPaginationComponent],
      declarations: [ProductDetailComponent, TranslatePipe, TrcurrencyPipe],
      providers: [
        provideRouter([{ path: 'products/:id', component: ProductDetailComponent }]),
        { provide: AccountService, useValue: { userProfile: profile.asReadonly() } },
        {
          provide: ApiService,
          useValue: {
            productDetail: detail,
            productAvailability: availability,
            productReviews: reviews,
            addOrder: addBasket,
          },
        },
      ],
    }).compileComponents();
    harness = await RouterTestingHarness.create();
  });

  it('loads a directly reachable product and preserves the prior search URL', async () => {
    const component = await harness.navigateByUrl(
      '/products/1?q=laptop&merchantId=4&sort=rating&page=1',
      ProductDetailComponent,
    );
    expect(detail).toHaveBeenCalledOnceWith(1);
    expect(availability).toHaveBeenCalledOnceWith([1]);
    expect(component.canAddToBasket).toBeTrue();
    expect(harness.routeNativeElement?.querySelector('h1')?.textContent).toContain(product.name);
    expect(harness.routeNativeElement?.querySelector('.product-context a')?.getAttribute('href')).toBe(
      '/dashboard?q=laptop&merchantId=4&sort=rating&page=1',
    );
  });

  it('keeps a public product available with a sign-in link and return to the source store', async () => {
    profile.set(null);
    const component = await harness.navigateByUrl('/products/1?storeId=4&storePage=2', ProductDetailComponent);

    expect(component.product).toEqual(product);
    expect(component.canAddToBasket).toBeFalse();
    expect(detail).toHaveBeenCalledOnceWith(1);
    expect(availability).not.toHaveBeenCalled();
    expect(harness.routeNativeElement?.querySelector('.product-context a')?.getAttribute('href')).toBe(
      '/stores/4?page=2',
    );
    const signIn = harness.routeNativeElement?.querySelector('.buy-button')?.getAttribute('href');
    expect(TestBed.inject(Router).parseUrl(signIn ?? '').queryParams['returnUrl']).toBe(
      '/products/1?storeId=4&storePage=2',
    );
  });

  it('restores the store filters and omits the unrelated store filter from the catalogue', async () => {
    await harness.navigateByUrl(
      '/products/1?storeId=4&storePage=2&q=coffee&minPrice=0&maxPrice=20&sort=price-desc&merchantId=99',
      ProductDetailComponent,
    );
    expect(harness.routeNativeElement?.querySelector('.product-context a')?.getAttribute('href')).toBe(
      '/stores/4?q=coffee&minPrice=0&maxPrice=20&sort=price-desc&page=2',
    );
  });

  it('preserves details on an inventory 503 and distinguishes the product becoming unavailable', async () => {
    detail.and.returnValues(
      of(product),
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );
    availability.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));

    const component = await harness.navigateByUrl('/products/1', ProductDetailComponent);
    expect(component.detailStatus).toBe('ready');
    expect(component.product?.description).toBe(product.description);
    expect(component.stock).toBeUndefined();
    expect(component.canAddToBasket).toBeFalse();

    component.loadProductDetail();
    expect(component.detailStatus).toBe('missing');
    expect(availability).toHaveBeenCalledOnceWith([1]);
  });

  it('ignores pending stock after a different product is opened', async () => {
    const nextProduct = { ...product, id: 2 };
    const previousStock = new Subject<Record<number, StockAvailability>>();
    detail.and.returnValues(of(product), of(nextProduct));
    availability.and.returnValues(previousStock, of({ 2: { productId: 2, listed: true, available: 0 } }));

    await harness.navigateByUrl('/products/1', ProductDetailComponent);
    const component = await harness.navigateByUrl('/products/2', ProductDetailComponent);
    previousStock.next({ 1: { productId: 1, listed: true, available: 8 } });

    expect(component.stock?.productId).toBe(2);
    expect(component.canAddToBasket).toBeFalse();
  });

  it('paginates a complete embedded review response without calling the review endpoint', async () => {
    const embedded = Array.from({ length: 66 }, (_, index) => ({ ...review, id: 66 - index }));
    detail.and.returnValue(of({ ...product, reviews: embedded, reviewCount: embedded.length }));
    const component = await harness.navigateByUrl('/products/1', ProductDetailComponent);
    const page = () => harness.routeNativeElement?.querySelectorAll('.review-list > li').length;
    const pagination = harness.routeNativeElement?.querySelector('.review-panel .pagination-bar');

    expect(page()).toBe(5);
    expect(component.reviews.map(({ id }) => id)).toEqual([66, 65, 64, 63, 62]);
    expect(component.reviewPages).toBe(14);

    pagination?.querySelector<HTMLButtonElement>(':scope > button:last-child')?.click();
    harness.detectChanges();
    expect(page()).toBe(5);
    expect(component.reviews.map(({ id }) => id)).toEqual([61, 60, 59, 58, 57]);

    pagination?.querySelector<HTMLButtonElement>('.page-numbers > button:last-child')?.click();
    harness.detectChanges();
    expect(page()).toBe(1);
    expect(component.reviews.map(({ id }) => id)).toEqual([1]);
    expect(component.reviewPage).toBe(13);
    expect(reviews).not.toHaveBeenCalled();
  });

  it('keeps the current review page when a later server page fails, then recovers on retry', async () => {
    const nextReview = { ...review, id: 7, comment: 'Second page' };
    reviews.and.returnValues(
      throwError(() => new HttpErrorResponse({ status: 503 })),
      of({ reviews: [nextReview], total: 7, page: 1, size: 5 }),
    );

    const component = await harness.navigateByUrl('/products/1', ProductDetailComponent);
    component.loadReviews(1);
    expect(component.reviewPage).toBe(0);
    expect(component.reviews).toEqual([review]);
    expect(component.failedReviewPage).toBe(1);

    component.loadReviews(1);
    expect(reviews.calls.allArgs()).toEqual([
      [1, 1, 5],
      [1, 1, 5],
    ]);
    expect(component.reviewPage).toBe(1);
    expect(component.reviews).toEqual([nextReview]);
    expect(component.failedReviewPage).toBeUndefined();
  });

  it('accepts an available quantity and suppresses a second cart request while the first is pending', async () => {
    addBasket.and.returnValue(NEVER);
    const component = await harness.navigateByUrl('/products/1', ProductDetailComponent);

    component.changeQuantity(7);
    component.addToBasket();
    expect(addBasket).not.toHaveBeenCalled();

    component.changeQuantity(3);
    component.addToBasket();
    component.addToBasket();
    expect(addBasket).toHaveBeenCalledOnceWith(1, 3);
  });
});
