import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { provideRouter, Router, RouterModule } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of } from 'rxjs';
import { Product } from 'src/model/IProduct';
import { DashboardComponent } from './dashboard.component';
import { CatalogProductCardComponent } from './catalog-product-card.component';
import { CatalogSkuFacetsComponent } from './catalog-sku-facets.component';
import { CatalogSearchFormComponent } from './catalog-search-form.component';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { TranslatePipe } from '../pipes/translate.pipe';
import { ApiService } from '../services/api.service';

describe('DashboardComponent', () => {
  let harness: RouterTestingHarness;
  let search: jasmine.Spy;
  const product: Product = {
    id: 1,
    merchantId: 4,
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
    search = jasmine.createSpy('searchProducts').and.returnValue(
      of({
        products: [product],
        total: 14,
        page: 1,
        size: 12,
        skuFacets: [{ name: 'color', value: 'red', productCount: 2 }],
      }),
    );
    await TestBed.configureTestingModule({
      imports: [
        FormsModule,
        RouterModule,
        ListPaginationComponent,
        CatalogProductCardComponent,
        CatalogSkuFacetsComponent,
        CatalogSearchFormComponent,
      ],
      declarations: [DashboardComponent, TranslatePipe],
      providers: [
        provideRouter([{ path: 'dashboard', component: DashboardComponent }]),
        {
          provide: ApiService,
          useValue: { searchProducts: search, publicStores: () => of([]) },
        },
      ],
    }).compileComponents();
    harness = await RouterTestingHarness.create();
  });

  it('uses the server search total and effective page in its links', async () => {
    const component = await harness.navigateByUrl('/dashboard', DashboardComponent);
    expect(search).toHaveBeenCalledWith({ q: '', sort: 'default', page: 0, size: 12, includeSkuFacets: 'true' });
    expect(component.totalPages).toBe(2);
    expect(component.displayPage).toBe(1);
    expect(harness.routeNativeElement?.querySelector('.product-card')?.getAttribute('href')).toBe('/products/1?page=1');
  });

  it('uses saved filters and keeps them in product links', async () => {
    const component = await harness.navigateByUrl(
      '/dashboard?q=laptop&merchantId=4&minPrice=0&maxPrice=100&sort=rating&page=1&other=ignore',
      DashboardComponent,
    );
    expect(search).toHaveBeenCalledWith({
      q: 'laptop',
      merchantId: 4,
      minPrice: 0,
      maxPrice: 100,
      sort: 'rating',
      page: 1,
      size: 12,
      includeSkuFacets: 'true',
    });
    const link = harness.routeNativeElement?.querySelector('.product-card')?.getAttribute('href');
    expect(TestBed.inject(Router).parseUrl(link ?? '').queryParams).toEqual({
      q: 'laptop',
      merchantId: '4',
      minPrice: '0',
      maxPrice: '100',
      sort: 'rating',
      page: '1',
    });
    expect(component.appliedFilters.map((filter) => filter.field)).toEqual(['q', 'merchantId', 'minPrice', 'maxPrice']);
    component.removeFilter('maxPrice');
    await harness.fixture.whenStable();
    expect(search.calls.mostRecent().args[0]).toEqual({
      q: 'laptop',
      merchantId: 4,
      minPrice: 0,
      sort: 'rating',
      page: 0,
      size: 12,
      includeSkuFacets: 'true',
    });
  });

  it('applies advanced matching and preserves it in product links', async () => {
    const component = await harness.navigateByUrl('/dashboard?page=1', DashboardComponent);
    component.draft.q = 'coffee tea';
    component.draft.match = 'any';
    component.draft.exclude = 'decaf';
    component.applyFilters();
    await harness.fixture.whenStable();
    expect(search.calls.mostRecent().args[0]).toEqual({
      q: 'coffee tea',
      match: 'any',
      exclude: 'decaf',
      sort: 'default',
      page: 0,
      size: 12,
      includeSkuFacets: 'true',
    });
    const link = harness.routeNativeElement?.querySelector('.product-card')?.getAttribute('href');
    expect(TestBed.inject(Router).parseUrl(link ?? '').queryParams).toEqual({
      q: 'coffee tea',
      match: 'any',
      exclude: 'decaf',
      page: '1',
    });
    component.removeFilter('exclude');
    await harness.fixture.whenStable();
    expect(search.calls.mostRecent().args[0]['exclude']).toBeUndefined();
    component.draft.exclude = Array.from({ length: 13 }, (_, i) => String(i)).join(' ');
    expect(component.invalidExclude).toBeTrue();
  });

  it('applies a counted SKU option and clears it while preserving the keyword', async () => {
    await harness.navigateByUrl('/dashboard?q=laptop&page=1', DashboardComponent);
    const root = harness.routeNativeElement!;
    const red = root.querySelector<HTMLButtonElement>('.sku-value')!;
    expect(red.textContent).toContain('2');
    red.click();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(search.calls.mostRecent().args[0]).toEqual({
      q: 'laptop',
      sort: 'default',
      page: 0,
      size: 12,
      sku: 'color=red',
      includeSkuFacets: 'true',
    });
    expect(red.getAttribute('aria-pressed')).toBe('true');
    const link = root.querySelector('.product-card')?.getAttribute('href');
    expect(TestBed.inject(Router).parseUrl(link ?? '').queryParams['sku']).toBe('color=red');
    root.querySelector<HTMLButtonElement>('.sku-clear')!.click();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/dashboard?q=laptop');
  });

  it('expands advanced search and submits its fields with the keyword', async () => {
    await harness.navigateByUrl('/dashboard?q=monitor&page=1', DashboardComponent);
    const root = harness.routeNativeElement!;
    const panel = root.querySelector<HTMLElement>('#catalog-advanced')!;
    const toggle = root.querySelector<HTMLButtonElement>('.search-filters')!;
    expect(panel.hidden).toBeTrue();
    toggle.click();
    harness.detectChanges();
    expect(panel.hidden).toBeFalse();
    const exclude = panel.querySelector<HTMLInputElement>('[name=exclude]')!;
    exclude.value = 'curved';
    exclude.dispatchEvent(new Event('input', { bubbles: true }));
    root.querySelector('form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(panel.hidden).toBeTrue();
    expect(search.calls.mostRecent().args[0]).toEqual({
      q: 'monitor',
      sort: 'default',
      page: 0,
      size: 12,
      exclude: 'curved',
      includeSkuFacets: 'true',
    });
  });

  it('rejects invalid draft prices and resets applied filters', async () => {
    const component = await harness.navigateByUrl('/dashboard?q=notebook&page=1', DashboardComponent);
    component.draft.minPrice = 200;
    component.draft.maxPrice = 100;
    component.applyFilters();
    expect(component.invalidPrice).toBeTrue();
    expect(search).toHaveBeenCalledTimes(1);

    component.clearFilters();
    await harness.fixture.whenStable();
    expect(TestBed.inject(Router).url).toBe('/dashboard');
    expect(search.calls.mostRecent().args[0]).toEqual({
      q: '',
      sort: 'default',
      page: 0,
      size: 12,
      includeSkuFacets: 'true',
    });
  });
});
