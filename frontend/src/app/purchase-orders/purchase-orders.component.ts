import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { finalize } from 'rxjs';
import { PurchaseOrder } from 'src/model/IPurchaseOrder';
import { ApiService } from '../services/api.service';

@Component({standalone:false,selector:'app-purchase-orders',templateUrl:'./purchase-orders.component.html'})
export class PurchaseOrdersComponent implements OnInit {
  orders:PurchaseOrder[]=[]; message=''; paying=new Set<number>();
  constructor(private api:ApiService,private changeDetector:ChangeDetectorRef){}
  ngOnInit():void{this.reload();}
  reload():void{this.api.purchaseOrders().subscribe(v=>{this.orders=v;this.changeDetector.markForCheck();});}
  pay(order:PurchaseOrder,fail=false):void{
    this.paying.add(order.id); const key=crypto.randomUUID();
    this.api.payOrder(order.id,key,fail).pipe(finalize(()=>{
      this.paying.delete(order.id);
      this.changeDetector.markForCheck();
    })).subscribe({next:v=>{this.orders=this.orders.map(o=>o.id===v.id?v:o);this.message=fail?'Simulated payment failed; the order remains payable.':'Payment successful.';this.changeDetector.markForCheck();},error:e=>{this.message=e.error?.message??'Payment failed.';}});
  }
}
