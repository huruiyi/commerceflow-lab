import {ComponentFixture, TestBed} from '@angular/core/testing';
import {of, throwError} from 'rxjs';
import {PurchaseOrder} from 'src/model/IPurchaseOrder';
import {PurchaseOrdersComponent} from './purchase-orders.component';
import {ApiService} from '../services/api.service';
import {TranslatePipe} from '../pipes/translate.pipe';
import {TrdatePipe} from '../pipes/trdate.pipe';
import {TrcurrencyPipe} from '../pipes/trcurrency.pipe';

describe('PurchaseOrdersComponent', () => {
  let fixture: ComponentFixture<PurchaseOrdersComponent>;
  let api: {
    purchaseOrders: jasmine.Spy;
    allProducts: jasmine.Spy;
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
    items: [{
      id: 1,
      productId: 1,
      productName: 'Learning Item',
      thumbnailUrl: 'item.png',
      unitPrice: 99,
      quantity: 1,
      subtotal: 99
    }],
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
      label: 'Home'
    }
  };
  beforeEach(async () => {
    sessionStorage.clear();
    api = {
      purchaseOrders: jasmine.createSpy().and.returnValue(of([order])),
      allProducts: jasmine.createSpy().and.returnValue(of([])),
      payOrder: jasmine.createSpy().and.returnValue(of({
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
          createdAt: '2026-11-10T00:00:00Z'
        }
      })),
      reviewOrderItem: jasmine.createSpy().and.returnValue(of({})),
      requestRefund: jasmine.createSpy().and.returnValue(of({
        refundNumber: 'REF-1',
        orderId: 42,
        status: 'SUCCEEDED',
        amount: 99,
        currency: 'CNY',
        reasonCode: 'CUSTOMER_CANCELLED',
        reasonDetail: '',
        requestedAt: '2026-11-10T01:00:00Z',
        events: []
      })),
      receiveOrder: jasmine.createSpy().and.returnValue(of({...order, status: 'RECEIVED'}))
    };
    await TestBed.configureTestingModule({
      declarations: [PurchaseOrdersComponent, TranslatePipe, TrdatePipe, TrcurrencyPipe],
      providers: [{provide: ApiService, useValue: api}]
    }).compileComponents();
    fixture = TestBed.createComponent(PurchaseOrdersComponent);
    fixture.detectChanges();
  });
  it('loads orders and creates a browser-session idempotency key', () => {
    expect(fixture.componentInstance.orders.length).toBe(1);
    expect(fixture.componentInstance.draftKeys[42]).toBeTruthy();
  });
  it('opens the newest order by default and lets the customer collapse it', () => {
    const component = fixture.componentInstance;
    expect(component.expandedOrders.has(42)).toBeTrue();
    component.toggleDetails(order);
    expect(component.expandedOrders.has(42)).toBeFalse();
  });
  it('renders aligned summary fields for identity, status, amount and actions', () => {
    const header = fixture.nativeElement.querySelector('.order-header') as HTMLElement;
    expect(header.querySelector('.order-meta')?.textContent).toContain('1 items');
    expect(header.querySelector('.order-state')?.textContent).toContain('Pending payment');
    expect(header.querySelector('.order-total')?.textContent).toContain('99');
    expect(header.querySelector('.summary-actions .primary-action-slot')).not.toBeNull();
  });
  it('only repeats payment state when it still needs attention', () => {
    const component = fixture.componentInstance;
    expect(component.showPaymentState({...order, payment: {
      paymentNumber: 'PAY-1',
      merchantNo: 'merchant-1',
      channel: 'ALIPAY',
      channelTransactionNo: 'channel-1',
      status: 'SUCCESS',
      failureReason: '',
      amount: 99,
      refundedAmount: 0,
      currency: 'CNY',
      createdAt: '2026-11-10T00:00:00Z'
    }})).toBeFalse();
    expect(component.showPaymentState({...order, payment: {
      paymentNumber: 'PAY-2',
      merchantNo: 'merchant-1',
      channel: 'ALIPAY',
      channelTransactionNo: 'channel-2',
      status: 'FAILED',
      failureReason: 'Simulated',
      amount: 99,
      refundedAmount: 0,
      currency: 'CNY',
      createdAt: '2026-11-10T00:00:00Z'
    }})).toBeTrue();
  });
  it('does not render empty history and replaces unavailable product images', () => {
    const component = fixture.componentInstance;
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
      events: [{id: 1, eventType: 'ORDER_SHIPPED', createdAt: '2026-11-10T01:00:00Z'}, {
        id: 2,
        eventType: 'SHIPMENT_UPDATED',
        detail: 'Carrier / TRACK-1 -> Carrier / TRACK-1',
        createdAt: '2026-11-10T02:00:00Z'
      }, {
        id: 3,
        eventType: 'SHIPMENT_UPDATED',
        detail: 'Carrier / TRACK-1 -> Carrier / TRACK-2',
        createdAt: '2026-11-10T03:00:00Z'
      }]
    });
    expect(events.map(event => event.id)).toEqual([1, 3]);
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
    api.payOrder.and.returnValues(of({
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
        createdAt: '2026-11-10T00:00:00Z'
      }
    }), throwError(() => ({error: {message: 'Idempotency conflict'}})));
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
  it('opens a full-refund form and preserves its idempotency key', () => {
    const component = fixture.componentInstance;
    component.showRefund(order);
    const key = component.refundKeys[42];
    expect(component.expandedOrders.has(42)).toBeTrue();
    expect(component.refundForms.has(42)).toBeTrue();
    component.requestRefund(order);
    expect(api.requestRefund).toHaveBeenCalledOnceWith(42, key, 'CUSTOMER_CANCELLED', '');
    expect(component.orders[0].status).toBe('REFUNDED');
    expect(component.refundForms.has(42)).toBeFalse();
  });
});
