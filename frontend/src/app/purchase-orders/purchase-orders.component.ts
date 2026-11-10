import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { finalize } from 'rxjs';
import { PaymentChannel, PurchaseOrder } from 'src/model/IPurchaseOrder';
import { ApiService } from '../services/api.service';

interface ChannelOption {
  code: PaymentChannel; name: string; merchantNo: string; appId: string;
  protocol: string; description: string; badge: string;
}
interface PaymentAttempt { key: string; channel: PaymentChannel; simulateFailure: boolean; paymentNumber?: string; }
interface Notice { type: 'success'|'warning'|'danger'|'info'; text: string; }

@Component({standalone:false,selector:'app-purchase-orders',templateUrl:'./purchase-orders.component.html',styleUrls:['./purchase-orders.component.css']})
export class PurchaseOrdersComponent implements OnInit {
  readonly channels:ChannelOption[]=[
    {code:'ALIPAY',name:'Alipay / 支付宝',merchantNo:'ALIPAY_MERCHANT_10001',appId:'2026111000000001',protocol:'RSA-SHA256',description:'App payment with an RSA-signed merchant request.',badge:'A'},
    {code:'WECHAT_PAY',name:'WeChat Pay / 微信支付',merchantNo:'1900001001',appId:'wx202611100001',protocol:'HMAC-SHA256',description:'QR-style payment using the demo API secret.',badge:'W'},
    {code:'UNION_PAY',name:'UnionPay / 银联支付',merchantNo:'898310000000001',appId:'UP202611100001',protocol:'RSA-SHA256',description:'Gateway payment with a simulated UnionPay certificate.',badge:'U'}
  ];
  orders:PurchaseOrder[]=[]; paying=new Set<number>(); selectedChannels:Record<number,PaymentChannel>={};
  draftKeys:Record<number,string>={}; lastAttempts:Record<number,PaymentAttempt>={}; notices:Record<number,Notice>={};
  constructor(private api:ApiService,private changeDetector:ChangeDetectorRef){}
  ngOnInit():void{this.reload();}
  reload():void{this.api.purchaseOrders().subscribe(orders=>{this.orders=orders;orders.forEach(order=>this.initializeOrder(order));this.changeDetector.markForCheck();});}
  selectChannel(order:PurchaseOrder,channel:PaymentChannel):void{this.selectedChannels[order.id]=channel;this.newAttempt(order);}
  newAttempt(order:PurchaseOrder):void{this.draftKeys[order.id]=crypto.randomUUID();delete this.notices[order.id];}
  pay(order:PurchaseOrder,simulateFailure=false):void{this.execute(order,{key:this.draftKeys[order.id]??crypto.randomUUID(),channel:this.selectedChannels[order.id]??'ALIPAY',simulateFailure},false);}
  replayLast(order:PurchaseOrder):void{const attempt=this.lastAttempts[order.id];if(attempt)this.execute(order,{...attempt},true);}
  replayOnAnotherChannel(order:PurchaseOrder):void{const previous=this.lastAttempts[order.id];if(!previous)return;const alternative=this.channels.find(channel=>channel.code!==previous.channel)?.code??'WECHAT_PAY';this.execute(order,{...previous,channel:alternative},true);}
  channel(order:PurchaseOrder):ChannelOption{const code=this.selectedChannels[order.id]??'ALIPAY';return this.channels.find(channel=>channel.code===code)??this.channels[0];}
  trackOrder(_index:number,order:PurchaseOrder):number{return order.id;}
  private execute(order:PurchaseOrder,attempt:PaymentAttempt,replay:boolean):void{
    this.paying.add(order.id);this.notices[order.id]={type:'info',text:replay?'Replaying the stored idempotent request…':'Submitting the payment request…'};
    this.api.payOrder(order.id,attempt.key,attempt.simulateFailure,attempt.channel).pipe(finalize(()=>{this.paying.delete(order.id);this.changeDetector.markForCheck();})).subscribe({
      next:updated=>{attempt.paymentNumber=updated.payment?.paymentNumber;this.lastAttempts[order.id]=attempt;this.persist(order.id,attempt);this.orders=this.orders.map(item=>item.id===updated.id?updated:item);if(attempt.simulateFailure)this.draftKeys[order.id]=crypto.randomUUID();this.notices[order.id]=attempt.simulateFailure?{type:'warning',text:`Declined as requested. Order remains payable and a new attempt key is ready. Payment ${attempt.paymentNumber}.`}:{type:'success',text:`Payment accepted. Order is now ${updated.status}. Payment ${attempt.paymentNumber}.`};},
      error:error=>{if(!replay||!this.lastAttempts[order.id]){this.lastAttempts[order.id]=attempt;this.persist(order.id,attempt);}this.notices[order.id]={type:'danger',text:error.error?.message??'Payment request failed. The same key is preserved for a safe retry.'};}
    });
  }
  private initializeOrder(order:PurchaseOrder):void{
    const stored=sessionStorage.getItem(this.storageKey(order.id));if(stored){try{const attempt=JSON.parse(stored) as PaymentAttempt;this.lastAttempts[order.id]=attempt;this.selectedChannels[order.id]=attempt.channel;this.draftKeys[order.id]=attempt.key;return;}catch{sessionStorage.removeItem(this.storageKey(order.id));}}
    this.selectedChannels[order.id]=(order.payment?.channel as PaymentChannel)??'ALIPAY';this.draftKeys[order.id]=crypto.randomUUID();
  }
  private persist(orderId:number,attempt:PaymentAttempt):void{sessionStorage.setItem(this.storageKey(orderId),JSON.stringify(attempt));}
  private storageKey(orderId:number):string{return `education-payment-attempt-${orderId}`;}
}
