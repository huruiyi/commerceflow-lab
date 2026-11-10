import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { forkJoin } from 'rxjs';
import { ShippingAddress, ShippingAddressRequest } from 'src/model/IShippingAddress';
import { ApiService } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

@Component({ standalone: false, selector: 'app-addresses', templateUrl: './addresses.component.html' })
export class AddressesComponent implements OnInit {
  addresses: ShippingAddress[] = [];
  selectedAddressId?: number;
  editingId?: number;
  message = '';
  draft: ShippingAddressRequest = this.empty();
  constructor(private api: ApiService, private changeDetector: ChangeDetectorRef, private i18n: I18nService) {}
  ngOnInit(): void { this.reload(); }
  reload(): void {
    forkJoin({addresses: this.api.getAddresses(), selected: this.api.selectedAddress()}).subscribe(({addresses, selected}) => {
      this.addresses=addresses; this.selectedAddressId=selected.addressId; this.changeDetector.markForCheck();
    });
  }
  save(): void {
    const request=this.editingId ? this.api.updateAddress(this.editingId, this.draft) : this.api.createAddress(this.draft);
    request.subscribe(() => { this.cancel(); this.message=this.i18n.t('addressSaved'); this.reload(); });
  }
  edit(a: ShippingAddress): void { this.editingId=a.id; this.draft={...a}; delete (this.draft as any).id; }
  cancel(): void { this.editingId=undefined; this.draft=this.empty(); }
  makeDefault(id:number): void { this.api.setDefaultAddress(id).subscribe(() => this.reload()); }
  select(id:number): void { this.api.selectAddress(id).subscribe(() => { this.selectedAddressId=id; this.message=this.i18n.t('checkoutAddressSelected'); this.changeDetector.markForCheck(); }); }
  remove(id:number): void { this.api.deleteAddress(id).subscribe(() => this.reload()); }
  private empty(): ShippingAddressRequest { return {recipientName:'',phone:'',countryCode:'CN',stateProvince:'',city:'',district:'',addressLine1:'',addressLine2:'',postalCode:'',label:'Home',isDefault:false}; }
}
