import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { RouterTestingModule } from '@angular/router/testing';
import { HlmButton } from '@spartan-ng/helm/button';
import { of, throwError } from 'rxjs';
import { PurchaseOrder } from 'src/model/IPurchaseOrder';
import { PurchaseOrdersComponent } from './purchase-orders.component';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { AccountService } from '../services/account.service';
import { ApiService } from '../services/api.service';
import { TranslatePipe } from '../pipes/translate.pipe';
import { TrdatePipe } from '../pipes/trdate.pipe';
import { TrcurrencyPipe } from '../pipes/trcurrency.pipe';
import { OrderListFilterComponent } from '../order-list-filter/order-list-filter.component';
import { LineAfterSalesComponent } from '../line-after-sales/line-after-sales.component';
import { AfterSaleTrackingComponent } from '../line-after-sales/after-sale-tracking.component';

describe('PurchaseOrdersComponent', () => {
  let fixture: ComponentFixture<PurchaseOrdersComponent>;
  let api: {
    searchOrders: jasmine.Spy;
    purchaseOrder: jasmine.Spy;
    payOrder: jasmine.Spy;
    reviewOrderItem: jasmine.Spy;
    requestRefund: jasmine.Spy;
    receiveOrder: jasmine.Spy;
  };
  const order: PurchaseOrder = {
    id: 42,
    orderNumber: 'ORD-42',
    status: 'PENDING_PAYMENT',
    totalAmount: 99,
    currency: 'CNY',
    createdAt: '2026-11-10T00:00:00Z',
    availableActions: ['PAY', 'REFRESH_PAYMENT', 'CANCEL_ORDER'],
    items: [
      {
        id: 1,
        productId: 1,
        productName: 'Learning Item',
        thumbnailUrl: 'item.png',
        unitPrice: 99,
        quantity: 1,
        subtotal: 99,
      },
    ],
    address: {
      recipientName: 'Demo',
      phone: '13800000000',
      countryCode: 'CN',
      stateProvince: 'Shanghai',
      city: 'Shanghai',
      district: 'Pudong',
      addressLine1: 'Learning Road',
      addressLine2: '',
      postalCode: '200120',
      label: 'Home',
    },
  };
  beforeEach(async () => {
    sessionStorage.clear();
    api = {
      searchOrders: jasmine.createSpy().and.returnValue(
        of({
          orders: [order],
          total: 1,
          page: 0,
          size: 10,
          pendingConfirm: 0,
          pendingShip: 0,
        }),
      ),
      purchaseOrder: jasmine.createSpy(),
      payOrder: jasmine.createSpy().and.returnValue(
        of({
          ...order,
          payment: {
            paymentNumber: 'PAY-1',
            merchantNo: '1900001001',
            channel: 'WECHAT_PAY',
            channelTransactionNo: 'MOCK-1',
            status: 'FAILED',
            failureReason: 'Simulated',
            amount: 99,
            currency: 'CNY',
            createdAt: '2026-11-10T00:00:00Z',
          },
        }),
      ),
      reviewOrderItem: jasmine.createSpy().and.returnValue(of({})),
      requestRefund: jasmine.createSpy().and.returnValue(
        of({
          refundNumber: 'REF-1',
          orderId: 42,
          status: 'SUCCEEDED',
          amount: 99,
          currency: 'CNY',
          reasonCode: 'CUSTOMER_CANCELLED',
          reasonDetail: '',
          requestedAt: '2026-11-10T01:00:00Z',
          events: [],
        }),
      ),
      receiveOrder: jasmine.createSpy().and.returnValue(of({ ...order, status: 'RECEIVED' })),
    };
    await TestBed.configureTestingModule({
      imports: [FormsModule, RouterTestingModule, ListPaginationComponent, HlmButton],
      declarations: [
        PurchaseOrdersComponent,
        OrderListFilterComponent,
        LineAfterSalesComponent,
        AfterSaleTrackingComponent,
        TranslatePipe,
        TrdatePipe,
        TrcurrencyPipe,
      ],
      providers: [{ provide: ApiService, useValue: api }],
    }).compileComponents();
    fixture = TestBed.createComponent(PurchaseOrdersComponent);
    fixture.detectChanges();
  });
  it('loads orders and creates a browser-session idempotency key', () => {
    expect(fixture.componentInstance.orders.length).toBe(1);
    expect(fixture.componentInstance.draftKeys[42]).toBeTruthy();
  });
  it('starts folded and lets the customer open and close the order details', () => {
    const button = fixture.nativeElement.querySelector('.order-summary-row button[aria-expanded]') as HTMLButtonElement;
    const details = fixture.nativeElement.querySelector('#order-inline-42') as HTMLElement;
    expect(button.getAttribute('aria-expanded')).toBe('false');
    expect(button.getAttribute('aria-controls')).toBe(details.id);
    expect(details.hidden).toBeTrue();
    button.click();
    fixture.detectChanges();
    expect(button.getAttribute('aria-expanded')).toBe('true');
    expect(details.querySelector('.payment-panel')).not.toBeNull();
    button.click();
    fixture.detectChanges();
    expect(details.hidden).toBeTrue();
  });
  it('renders a compact labeled table with a direct link and existing summary actions', () => {
    const panel = fixture.nativeElement.querySelector('.orders-panel') as HTMLElement;
    const row = panel.querySelector('.order-summary-row') as HTMLElement;
    expect(panel.querySelector('app-order-list-filter')).not.toBeNull();
    expect(Array.from(panel.querySelectorAll('thead th'), (item) => item.textContent?.trim())).toEqual([
      'Order number',
      'Products',
      'Status',
      'Amount',
      'Action',
    ]);
    expect(row.querySelector('.order-identity a')?.getAttribute('href')).toBe('/orders/42');
    expect(row.querySelector('.order-products')?.textContent).toContain('Learning Item');
    expect(row.querySelector('.order-state')?.textContent).toContain('Pending payment');
    expect(row.querySelector('.order-total')?.textContent).toContain('99');
    expect(row.querySelector('.summary-actions .primary-action-slot button')).not.toBeNull();
  });
  it('opens an older order with a pending payment or cancellation recovery and allows collapsing it', () => {
    const component = fixture.componentInstance;
    const profileId = TestBed.inject(AccountService).userProfile()?.id;
    const payment = { ...order, id: 41 };
    const cancellation = { ...order, id: 40 };
    sessionStorage.setItem(
      `commerceflow-order:${profileId}:${payment.id}:payment`,
      JSON.stringify({ key: 'original-key', channel: 'ALIPAY', simulateFailure: false, awaitingReceipt: true }),
    );
    sessionStorage.setItem(`commerceflow-order:${profileId}:${cancellation.id}:cancel`, 'Original reason');
    api.searchOrders.and.returnValue(of({ orders: [order, payment, cancellation], total: 3, page: 0, size: 10 }));
    component.reload();
    fixture.detectChanges();

    expect([...component.expandedOrders]).toEqual([41, 40]);
    expect(component.lastAttempts[41].key).toBe('original-key');
    expect(component.cancellationRequests[40]).toBe('Original reason');
    expect(fixture.nativeElement.querySelector('#order-inline-41 .payment-panel')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('#order-inline-40 .cancel-order-form')).not.toBeNull();

    component.toggleDetails(payment);
    component.reload();
    expect([...component.expandedOrders]).toEqual([40]);
  });
  it('only repeats payment state when it still needs attention', () => {
    const component = fixture.componentInstance;
    expect(
      component.showPaymentState({
        ...order,
        payment: {
          paymentNumber: 'PAY-1',
          merchantNo: 'merchant-1',
          channel: 'ALIPAY',
          channelTransactionNo: 'channel-1',
          status: 'SUCCESS',
          failureReason: '',
          amount: 99,
          refundedAmount: 0,
          currency: 'CNY',
          createdAt: '2026-11-10T00:00:00Z',
        },
      }),
    ).toBeFalse();
    expect(
      component.showPaymentState({
        ...order,
        payment: {
          paymentNumber: 'PAY-2',
          merchantNo: 'merchant-1',
          channel: 'ALIPAY',
          channelTransactionNo: 'channel-2',
          status: 'FAILED',
          failureReason: 'Simulated',
          amount: 99,
          refundedAmount: 0,
          currency: 'CNY',
          createdAt: '2026-11-10T00:00:00Z',
        },
      }),
    ).toBeTrue();
  });
  it('shows payment waiting on direct detail and clears it when the same order advances', () => {
    const component = fixture.componentInstance;
    const awaiting: PurchaseOrder = {
      ...order,
      status: 'PAID',
      inventoryStatus: 'WAITING_COMMIT',
      availableActions: [],
      payment: {
        paymentNumber: 'PAY-42',
        merchantNo: 'merchant-1',
        channel: 'ALIPAY',
        channelTransactionNo: 'channel-42',
        status: 'SUCCESS',
        failureReason: '',
        amount: 99,
        refundedAmount: 0,
        currency: 'CNY',
        createdAt: '2026-11-10T00:00:00Z',
      },
    };
    api.purchaseOrder.and.returnValues(
      of(awaiting),
      of({ ...awaiting, status: 'PAID', inventoryStatus: 'COMMITTED', availableActions: [] }),
    );
    component.detailId = '42';
    component.reload();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('#payment [role="status"]')?.textContent).toContain(
      'Payment succeeded. The order is still updating.',
    );

    component.refresh(awaiting);
    fixture.detectChanges();
    expect(api.purchaseOrder.calls.allArgs()).toEqual([[42], [42]]);
    expect(component.orders[0].payment?.paymentNumber).toBe('PAY-42');
    expect(fixture.nativeElement.querySelector('#payment [role="status"]')).toBeNull();
  });
  it('does not render empty history and replaces unavailable product images', () => {
    const component = fixture.componentInstance;
    component.toggleDetails(order);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.order-history')).toBeNull();
    component.markProductImageUnavailable(1);
    fixture.detectChanges();
    expect(component.unavailableProductImages.has(1)).toBeTrue();
    expect(fixture.nativeElement.querySelector('.item-thumbnail img')).toBeNull();
    expect(fixture.nativeElement.querySelector('.item-thumbnail svg')).not.toBeNull();
  });
  it('removes historical no-op shipment updates from the customer timeline', () => {
    const component = fixture.componentInstance;
    const events = component.visibleOrderEvents({
      ...order,
      events: [
        { id: 1, eventType: 'ORDER_SHIPPED', createdAt: '2026-11-10T01:00:00Z' },
        {
          id: 2,
          eventType: 'SHIPMENT_UPDATED',
          detail: 'Carrier / TRACK-1 -> Carrier / TRACK-1',
          createdAt: '2026-11-10T02:00:00Z',
        },
        {
          id: 3,
          eventType: 'SHIPMENT_UPDATED',
          detail: 'Carrier / TRACK-1 -> Carrier / TRACK-2',
          createdAt: '2026-11-10T03:00:00Z',
        },
      ],
    });
    expect(events.map((event) => event.id)).toEqual([1, 3]);
  });
  it('switches payment channel and creates a fresh key', () => {
    const component = fixture.componentInstance;
    const oldKey = component.draftKeys[42];
    component.selectChannel(order, 'WECHAT_PAY');
    expect(component.selectedChannels[42]).toBe('WECHAT_PAY');
    expect(component.draftKeys[42]).not.toBe(oldKey);
  });
  it('submits and replays the exact idempotent request', () => {
    const component = fixture.componentInstance;
    component.selectChannel(order, 'WECHAT_PAY');
    component.pay(order, true);
    const key = component.lastAttempts[42].key;
    component.replayLast(order);
    expect(api.payOrder.calls.argsFor(0)).toEqual([42, key, true, 'WECHAT_PAY']);
    expect(api.payOrder.calls.argsFor(1)).toEqual([42, key, true, 'WECHAT_PAY']);
  });
  it('keeps the valid replay request after a cross-channel conflict', () => {
    const component = fixture.componentInstance;
    api.payOrder.and.returnValues(
      of({
        ...order,
        payment: {
          paymentNumber: 'PAY-1',
          merchantNo: '1900001001',
          channel: 'WECHAT_PAY',
          channelTransactionNo: 'MOCK-1',
          status: 'FAILED',
          failureReason: 'Simulated',
          amount: 99,
          currency: 'CNY',
          createdAt: '2026-11-10T00:00:00Z',
        },
      }),
      throwError(() => ({ error: { message: 'Idempotency conflict' } })),
    );
    component.selectChannel(order, 'WECHAT_PAY');
    component.pay(order, true);
    component.replayOnAnotherChannel(order);
    expect(component.lastAttempts[42].channel).toBe('WECHAT_PAY');
    expect(component.notices[42].text).toBe('Idempotency conflict');
  });
  it('requires review text and submits the selected score once', () => {
    const component = fixture.componentInstance;
    component.setRating(1, 4);
    component.review(order, 1);
    expect(api.reviewOrderItem).not.toHaveBeenCalled();
    component.reviewComments[1] = '  Useful learning product.  ';
    component.review(order, 1);
    expect(api.reviewOrderItem).toHaveBeenCalledOnceWith(1, 4, 'Useful learning product.');
    expect(component.reviewed.has(1)).toBeTrue();
  });
  it('opens a full-refund form and preserves its idempotency key', async () => {
    const component = fixture.componentInstance;
    api.purchaseOrder.and.returnValue(
      of({
        ...order,
        status: 'REFUNDED',
        refund: { refundNumber: 'REF-1', status: 'SUCCEEDED' },
      }),
    );
    component.showRefund(order);
    const key = component.refundKeys[42];
    expect(component.expandedOrders.has(42)).toBeTrue();
    expect(component.refundForms.has(42)).toBeTrue();
    component.requestRefund(order);
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(api.requestRefund).toHaveBeenCalledOnceWith(42, key, 'CUSTOMER_CANCELLED', '');
    expect(component.orders[0].status).toBe('REFUNDED');
    expect(component.refundForms.has(42)).toBeFalse();
  });
});
