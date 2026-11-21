import { Component, Input } from '@angular/core';
import { OrderLogistics } from 'src/model/IPurchaseOrder';

@Component({
  standalone: false,
  selector: 'app-after-sale-tracking',
  templateUrl: './after-sale-tracking.component.html',
})
export class AfterSaleTrackingComponent {
  @Input({ required: true }) title!: string;
  @Input() parcel?: OrderLogistics | null;
}
