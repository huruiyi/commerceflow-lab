import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { provideRouter, Router, RouterModule } from '@angular/router';
import { of, throwError } from 'rxjs';
import { OrderList } from 'src/model/IOrderList';
import { CatalogImageComponent } from '../dashboard/catalog-image.component';
import { TranslatePipe } from '../pipes/translate.pipe';
import { TrcurrencyPipe } from '../pipes/trcurrency.pipe';
import { ApiService } from '../services/api.service';
import { OrderComponent } from './order.component';

describe('OrderComponent', () => {
  let component: OrderComponent;
  let fixture: ComponentFixture<OrderComponent>;
  let getOrders: jasmine.Spy;
  let checkoutQuote: jasmine.Spy;
  let checkout: jasmine.Spy;
  const order: OrderList = {
    id: 21,
    productId: 1,
    createdAt: '2026-11-10T00:00:00Z',
    productName: 'Learning Laptop',
    price: 100,
    quantity: 2,
    totalPrice: 200,
    thumbnailUrl: '',
    note: '',
  };

  beforeEach(async () => {
    getOrders = jasmine.createSpy('getOrders').and.returnValue(of([]));
    checkoutQuote = jasmine.createSpy('checkoutQuote').and.returnValue(
      of({
        quoteId: 'QUOTE-21',
        productSubtotal: 200,
        discountAmount: 10,
        shippingFee: 5,
        totalAmount: 195,
      }),
    );
    checkout = jasmine.createSpy('checkout').and.returnValue(of({}));
    await TestBed.configureTestingModule({
      imports: [FormsModule, RouterModule, CatalogImageComponent],
      declarations: [OrderComponent, TranslatePipe, TrcurrencyPipe],
      providers: [
        provideRouter([]),
        {
          provide: ApiService,
          useValue: {
            getOrders,
            cartProducts: () => of(undefined),
            productAvailability: () => of({}),
            getAddresses: () => of([{ id: 8, recipientName: 'Demo', isDefault: true }]),
            selectedAddress: () => of({}),
            couponWallet: () => of([]),
            checkoutQuote,
            checkout,
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(OrderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('offers a return to the storefront when the cart is empty', () => {
    expect(fixture.nativeElement.querySelector('.cart-empty a')?.getAttribute('href')).toBe('/dashboard');
    expect(fixture.nativeElement.querySelector('.cart-checkout')).toBeNull();
  });

  it('links product names and uses selected cart line IDs when quoting and confirming', () => {
    const secondary = { ...order, id: 22, productId: 4, productName: 'Learning Headphones' };
    getOrders.and.returnValue(of([order, secondary]));
    component.refreshCart();
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    expect(Array.from(root.querySelectorAll('.cart-product-link'), (link) => link.getAttribute('href'))).toEqual([
      '/products/1',
      '/products/4',
    ]);
    const rows = root.querySelectorAll('.cart-item');
    rows[1].querySelector<HTMLInputElement>('input[type=checkbox]')?.click();
    fixture.detectChanges();
    expect(root.querySelector<HTMLInputElement>('.cart-items-toolbar input')?.indeterminate).toBeTrue();

    const submit = root.querySelector<HTMLButtonElement>('.cart-submit');
    submit?.click();
    fixture.detectChanges();
    expect(checkoutQuote).toHaveBeenCalledOnceWith({ addressId: 8, itemIds: [21], couponId: undefined });
    expect(root.querySelector('.cart-payable dd')?.textContent).toContain('195');

    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    submit?.click();
    expect(checkout).toHaveBeenCalledOnceWith({ addressId: 8, itemIds: [21], couponId: undefined }, 'QUOTE-21');
    expect(navigate).toHaveBeenCalledOnceWith(['/orders']);
  });

  it('shows the affected cart line and prevents checkout after a stock change', () => {
    getOrders.and.returnValue(of([order]));
    checkoutQuote.and.returnValue(
      throwError(
        () =>
          new HttpErrorResponse({
            status: 409,
            error: {
              code: 'CHECKOUT_STOCK_CHANGED',
              lines: [{ productId: 1, requested: 2, available: 1, listed: true }],
            },
          }),
      ),
    );
    component.refreshCart();
    fixture.detectChanges();

    const root = fixture.nativeElement as HTMLElement;
    const submit = root.querySelector<HTMLButtonElement>('.cart-submit');
    submit?.click();
    fixture.detectChanges();
    expect(root.querySelector('.cart-stock[role=status]')?.textContent).toContain('1 available');
    expect(root.querySelector('.cart-checkout-error[role=alert]')).not.toBeNull();
    expect(submit?.disabled).toBeTrue();
    expect(checkout).not.toHaveBeenCalled();
  });
});
