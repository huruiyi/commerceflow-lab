import { ComponentFixture, TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { RouterTestingModule } from '@angular/router/testing';
import { of, Subject, throwError } from 'rxjs';

import { DashboardComponent } from './dashboard.component';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { TranslatePipe } from '../pipes/translate.pipe';
import { ApiService, StockAvailability } from '../services/api.service';
import { Product } from 'src/model/IProduct';

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let fixture: ComponentFixture<DashboardComponent>;
  let search: jasmine.Spy;
  let detail: jasmine.Spy;
  let availability: jasmine.Spy;
  const product: Product = {
    id: 1,
    name: 'Product',
    brief: '',
    description: 'Authoritative details',
    price: 10,
    imageUrl: '',
    thumbnailUrl: '',
    averageRating: 0,
    reviewCount: 0,
    reviews: [],
  };

  beforeEach(async () => {
    search = jasmine.createSpy('searchProducts').and.returnValue(of({ products: [], total: 14, page: 1, size: 12 }));
    detail = jasmine.createSpy('productDetail');
    availability = jasmine.createSpy('productAvailability');
    await TestBed.configureTestingModule({
      imports: [RouterTestingModule, FormsModule, ListPaginationComponent],
      declarations: [DashboardComponent, TranslatePipe],
      providers: [
        {
          provide: ApiService,
          useValue: {
            searchProducts: search,
            publicStores: () => of([]),
            productDetail: detail,
            productAvailability: availability,
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('uses the server search total and effective page', () => {
    expect(search).toHaveBeenCalledWith({ q: '', sort: 'default', page: 0, size: 12 });
    expect(component.totalPages).toBe(2);
    expect(component.page).toBe(1);
  });

  it('preserves details on a stock error and uses the catalog result when retrying', () => {
    component.products = [product];
    detail.and.returnValues(
      of(product),
      throwError(() => new HttpErrorResponse({ status: 404 })),
    );
    availability.and.returnValue(throwError(() => new HttpErrorResponse({ status: 503 })));

    component.fncSelectDetail(0);
    expect(component.detailStatus).toBe('ready');
    expect(component.selectedProduct?.description).toBe(product.description);
    expect(component.stock).toBeUndefined();
    expect(component.canAddToBasket).toBeFalse();

    component.loadProductDetail();
    expect(component.detailStatus).toBe('missing');
    expect(availability).toHaveBeenCalledOnceWith([1]);
  });

  it('ignores a pending stock result after a different product is opened', () => {
    const nextProduct = { ...product, id: 2 };
    const previousStock = new Subject<Record<number, StockAvailability>>();
    component.products = [product, nextProduct];
    detail.and.returnValues(of(product), of(nextProduct));
    availability.and.returnValues(previousStock, of({ 2: { productId: 2, listed: true, available: 0 } }));

    component.fncSelectDetail(0);
    component.fncSelectDetail(1);
    previousStock.next({ 1: { productId: 1, listed: true, available: 8 } });

    expect(component.stock?.productId).toBe(2);
    expect(component.canAddToBasket).toBeFalse();
  });
});
