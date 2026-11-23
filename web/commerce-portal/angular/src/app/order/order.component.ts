import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { OrderList } from 'src/model/IOrderList';
import { ApiService, CheckoutQuote, CustomerCoupon, StockAvailability } from '../services/api.service';
import { SeoService } from '../services/seo.service';
import { Router } from '@angular/router';
import { I18nService } from '../services/i18n.service';
import { catchError, finalize, forkJoin, of, switchMap } from 'rxjs';
import { ShippingAddress } from 'src/model/IShippingAddress';

@Component({
  standalone: false,
  selector: 'app-order',
  templateUrl: './order.component.html',
  styleUrls: ['./order.component.css'],
})
export class OrderComponent implements OnInit {
  customerOrders: OrderList[] = [];
  updatingOrderIds = new Set<number>();
  checkoutError = '';
  quote?: CheckoutQuote;
  selectedCoupon?: string;
  availableCoupons?: CustomerCoupon[];
  loadingCoupons = false;
  couponLoadFailed = false;

  loadCoupons(): void {
    if (this.loadingCoupons) return;
    this.loadingCoupons = true;
    this.couponLoadFailed = false;
    this.api
      .couponWallet()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.loadingCoupons = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: (items) => {
          this.availableCoupons = items.filter((item) => item.status === 'AVAILABLE' && item.enabled);
          if (this.selectedCoupon && !this.availableCoupons.some((item) => item.id === this.selectedCoupon)) {
            this.selectedCoupon = undefined;
            this.invalidateQuote();
          }
        },
        error: () => (this.couponLoadFailed = true),
      });
  }
  checkingOut = false;
  loadingAddresses = true;
  addressError = false;
  addresses: ShippingAddress[] = [];
  addressId?: number;
  stocks: Record<number, StockAvailability> = {};
  catalogIds?: Set<number>;
  private stockLines: Record<number, { listed: boolean; available: number | null; requested: number }> = {};
  loadingCart = false;
  cartLoadFailed = false;
  changingCart = false;
  private cartVersion = 0;
  private readonly unselectedProducts = new Set<number>();

  get selectedItems(): OrderList[] {
    return this.customerOrders.filter((item) => this.isSelected(item));
  }

  get selectedQuantity(): number {
    return this.selectedItems.reduce((quantity, item) => quantity + item.quantity, 0);
  }

  get canCheckout(): boolean {
    return (
      !!this.addressId &&
      !this.loadingAddresses &&
      !this.addressError &&
      !this.checkingOut &&
      !this.loadingCart &&
      !this.cartLoadFailed &&
      !this.changingCart &&
      !this.updatingOrderIds.size &&
      this.selectedItems.length > 0 &&
      !this.hasStockProblems
    );
  }

  isSelected(item: OrderList): boolean {
    return !this.unselectedProducts.has(item.productId);
  }

  selectItem(item: OrderList, selected: boolean): void {
    if (selected) {
      this.unselectedProducts.delete(item.productId);
    } else {
      this.unselectedProducts.add(item.productId);
    }
    this.invalidateQuote();
  }

  selectAll(selected: boolean): void {
    this.customerOrders.forEach((item) => this.selectItem(item, selected));
  }

  refreshCart(): void {
    this.stockLines = {};
    this.checkoutError = '';
    this.loadCart();
  }

  get hasStockProblems(): boolean {
    return this.selectedItems.some((order) => !!this.stockProblem(order));
  }

  stockProblem(order: OrderList): string {
    const line = this.stockLines[order.productId];
    if (line && line.requested === order.quantity) {
      if (!line.listed) return this.i18n.t('catalogInactive');
      if (line.available !== null) return this.stockAdjustment(order.quantity, line.available);
    }
    if (this.catalogIds && !this.catalogIds.has(order.productId)) return this.i18n.t('catalogInactive');
    const stock = this.stocks[order.productId];
    if (stock && !stock.listed) return this.i18n.t('catalogInactive');
    return stock && stock.available < order.quantity ? this.stockAdjustment(order.quantity, stock.available) : '';
  }

  private stockAdjustment(quantity: number, available: number): string {
    return this.i18n.t('stockQuantityAdjustment', { available, reduceBy: quantity - available });
  }

  constructor(
    private api: ApiService,
    private seoService: SeoService,
    private router: Router,
    private changeDetector: ChangeDetectorRef,
    public i18n: I18nService,
    private destroyRef: DestroyRef,
  ) {
    seoService.setTitle(i18n.t('shoppingCart'));
    seoService.setMeta(i18n.t('shoppingCart'));
  }

  ngOnInit(): void {
    this.loadCart();
    this.loadAddresses();
    this.loadCoupons();
    this.i18n.localeChanged.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(() => this.loadCart());
  }

  loadAddresses(): void {
    this.quote = undefined;
    this.loadingAddresses = true;
    this.addressError = false;
    forkJoin({ addresses: this.api.getAddresses(), selected: this.api.selectedAddress() })
      .pipe(
        finalize(() => {
          this.loadingAddresses = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: ({ addresses, selected }) => {
          this.addresses = addresses;
          this.addressId =
            addresses.find((address) => address.id === this.addressId)?.id ??
            addresses.find((address) => address.id === selected.addressId)?.id ??
            addresses.find((address) => address.isDefault)?.id;
        },
        error: () => {
          this.addressError = true;
        },
      });
  }

  private loadCart(): void {
    this.quote = undefined;
    const version = ++this.cartVersion;
    this.loadingCart = true;
    this.cartLoadFailed = false;
    this.api
      .getOrders()
      .pipe(
        switchMap((orders) =>
          forkJoin({
            orders: of(orders),
            products: this.api
              .cartProducts(orders.map((order) => order.productId))
              .pipe(catchError(() => of(undefined))),
            stocks: this.api.productAvailability(orders.map((order) => order.productId)).pipe(catchError(() => of({}))),
          }),
        ),
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          if (version !== this.cartVersion) return;
          this.loadingCart = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: ({ orders, products, stocks }) => {
          if (version !== this.cartVersion) return;
          this.catalogIds = products ? new Set(Object.keys(products).map(Number)) : undefined;
          this.stocks = stocks;
          const productIds = new Set(orders.map((item) => item.productId));
          for (const id of this.unselectedProducts) {
            if (!productIds.has(id)) this.unselectedProducts.delete(id);
          }
          this.customerOrders = orders.map((order) => {
            const product = products?.[order.productId];
            if (!product) return order;
            return {
              ...order,
              productName: product.name,
              price: product.price,
              totalPrice: (Math.round(product.price * 100) * order.quantity) / 100,
            };
          });
          this.changeDetector.markForCheck();
        },
        error: () => {
          if (version === this.cartVersion) this.cartLoadFailed = true;
        },
      });
  }

  changeQuantity(order: OrderList, quantity: number): void {
    const normalized = this.normalizeQuantity(quantity);
    if (
      normalized === order.quantity ||
      this.checkingOut ||
      this.loadingCart ||
      this.changingCart ||
      this.updatingOrderIds.has(order.id)
    ) {
      return;
    }

    this.invalidateQuote();
    this.updatingOrderIds.add(order.id);
    this.api.updateOrderQuantity(order.id, normalized).subscribe({
      next: (updated) => {
        delete this.stockLines[updated.productId];
        this.customerOrders = this.customerOrders.map((item) =>
          item.id === updated.id
            ? {
                ...item,
                quantity: updated.quantity,
                totalPrice: (Math.round(item.price * 100) * updated.quantity) / 100,
              }
            : item,
        );
        this.changeDetector.markForCheck();
      },
      error: (err) => {
        this.checkoutError = err.error?.message ?? this.i18n.t('requestFailed');
        this.updatingOrderIds.delete(order.id);
        this.changeDetector.markForCheck();
      },
      complete: () => {
        this.updatingOrderIds.delete(order.id);
        this.changeDetector.markForCheck();
      },
    });
  }

  quantityInput(order: OrderList, event: Event): void {
    this.changeQuantity(order, (event.target as HTMLInputElement).valueAsNumber);
  }

  remove(order: OrderList): void {
    if (this.checkingOut || this.changingCart || this.loadingCart || this.updatingOrderIds.size) return;
    this.invalidateQuote();
    this.changingCart = true;
    this.api
      .removeCartItem(order.id)
      .pipe(
        finalize(() => {
          this.changingCart = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: () => {
          this.customerOrders = this.customerOrders.filter((item) => item.id !== order.id);
          this.unselectedProducts.delete(order.productId);
        },
        error: () => {
          this.checkoutError = this.i18n.t('requestFailed');
        },
      });
  }

  clearCart(): void {
    if (this.checkingOut || this.changingCart || this.loadingCart || this.updatingOrderIds.size) return;
    this.invalidateQuote();
    this.changingCart = true;
    this.api
      .clearCart()
      .pipe(
        finalize(() => {
          this.changingCart = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: () => {
          this.customerOrders = [];
          this.unselectedProducts.clear();
        },
        error: () => {
          this.checkoutError = this.i18n.t('requestFailed');
        },
      });
  }

  invalidateQuote(): void {
    this.quote = undefined;
    this.checkoutError = '';
  }

  checkout(): void {
    const addressId = this.addressId;
    if (!addressId || !this.canCheckout) return;
    const selection = { addressId, itemIds: this.selectedItems.map((item) => item.id), couponId: this.selectedCoupon };
    this.checkingOut = true;
    this.checkoutError = '';
    if (!this.quote) {
      this.api
        .checkoutQuote(selection)
        .pipe(finalize(() => this.finishCheckoutRequest()))
        .subscribe({
          next: (quote) => (this.quote = quote),
          error: (error) => this.handleCheckoutError(error),
        });
      return;
    }
    this.api
      .checkout(selection, this.quote.quoteId)
      .pipe(finalize(() => this.finishCheckoutRequest()))
      .subscribe({
        next: () => void this.router.navigate(['/orders']),
        error: (error) => this.handleCheckoutError(error),
      });
  }

  private finishCheckoutRequest(): void {
    this.checkingOut = false;
    this.changeDetector.markForCheck();
  }

  private handleCheckoutError(error: import('@angular/common/http').HttpErrorResponse): void {
    if (error.error?.code === 'CHECKOUT_PRICE_CHANGED') {
      this.quote = error.error.quote;
      this.checkoutError = this.i18n.t('checkoutPriceChanged');
      return;
    }
    if (error.error?.code === 'CHECKOUT_STOCK_CHANGED') {
      this.stockLines = Object.fromEntries(
        (error.error.lines ?? []).map((line: { productId: number }) => [line.productId, line]),
      );
      this.checkoutError = this.i18n.t('checkoutStockChanged');
      this.loadCart();
      return;
    }
    this.checkoutError = error.error?.message ?? this.i18n.t('checkoutFailed');
    if (error.status >= 400 && error.status < 500) {
      this.loadAddresses();
      this.loadCart();
    }
  }

  private normalizeQuantity(quantity: number): number {
    if (!Number.isFinite(quantity)) {
      return 1;
    }
    return Math.min(99, Math.max(1, Math.trunc(quantity)));
  }
}
