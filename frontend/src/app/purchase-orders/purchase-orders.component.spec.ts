import { ComponentFixture,TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { PurchaseOrder } from 'src/model/IPurchaseOrder';
import { PurchaseOrdersComponent } from './purchase-orders.component';
import { ApiService } from '../services/api.service';

describe('PurchaseOrdersComponent',()=>{
  let fixture:ComponentFixture<PurchaseOrdersComponent>;
  let api:{purchaseOrders:jasmine.Spy;payOrder:jasmine.Spy};
  const order:PurchaseOrder={id:42,orderNumber:'ORD-42',status:'PENDING_PAYMENT',totalAmount:99,currency:'CNY',createdAt:'2026-11-10T00:00:00Z',availableActions:['PAY','REFRESH_PAYMENT','CANCEL_ORDER'],items:[{id:1,productId:1,productName:'Learning Item',thumbnailUrl:'item.png',unitPrice:99,quantity:1,subtotal:99}],address:{recipientName:'Demo',phone:'13800000000',countryCode:'CN',stateProvince:'Shanghai',city:'Shanghai',district:'Pudong',addressLine1:'Learning Road',addressLine2:'',postalCode:'200120',label:'Home'}};
  beforeEach(async()=>{
    sessionStorage.clear();
    api={purchaseOrders:jasmine.createSpy().and.returnValue(of([order])),payOrder:jasmine.createSpy().and.returnValue(of({...order,payment:{paymentNumber:'PAY-1',merchantNo:'1900001001',channel:'WECHAT_PAY',channelTransactionNo:'MOCK-1',status:'FAILED',failureReason:'Simulated',amount:99,currency:'CNY',createdAt:'2026-11-10T00:00:00Z'}}))};
    await TestBed.configureTestingModule({declarations:[PurchaseOrdersComponent],providers:[{provide:ApiService,useValue:api}]}).compileComponents();
    fixture=TestBed.createComponent(PurchaseOrdersComponent);fixture.detectChanges();
  });
  it('loads orders and creates a browser-session idempotency key',()=>{expect(fixture.componentInstance.orders.length).toBe(1);expect(fixture.componentInstance.draftKeys[42]).toBeTruthy();});
  it('switches payment channel and creates a fresh key',()=>{const component=fixture.componentInstance;const oldKey=component.draftKeys[42];component.selectChannel(order,'WECHAT_PAY');expect(component.selectedChannels[42]).toBe('WECHAT_PAY');expect(component.draftKeys[42]).not.toBe(oldKey);});
  it('submits and replays the exact idempotent request',()=>{const component=fixture.componentInstance;component.selectChannel(order,'WECHAT_PAY');component.pay(order,true);const key=component.lastAttempts[42].key;component.replayLast(order);expect(api.payOrder.calls.argsFor(0)).toEqual([42,key,true,'WECHAT_PAY']);expect(api.payOrder.calls.argsFor(1)).toEqual([42,key,true,'WECHAT_PAY']);});
  it('keeps the valid replay request after a cross-channel conflict',()=>{const component=fixture.componentInstance;api.payOrder.and.returnValues(of({...order,payment:{paymentNumber:'PAY-1',merchantNo:'1900001001',channel:'WECHAT_PAY',channelTransactionNo:'MOCK-1',status:'FAILED',failureReason:'Simulated',amount:99,currency:'CNY',createdAt:'2026-11-10T00:00:00Z'}}),throwError(()=>({error:{message:'Idempotency conflict'}})));component.selectChannel(order,'WECHAT_PAY');component.pay(order,true);component.replayOnAnotherChannel(order);expect(component.lastAttempts[42].channel).toBe('WECHAT_PAY');expect(component.notices[42].text).toBe('Idempotency conflict');});
});
