import {
  afterNextRender,
  ChangeDetectorRef,
  Component,
  DestroyRef,
  ElementRef,
  inject,
  Injector,
  OnInit,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, forkJoin, Observable } from 'rxjs';
import { ShippingAddress, ShippingAddressRequest } from 'src/model/IShippingAddress';
import { ApiService } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

@Component({
  standalone: false,
  selector: 'app-addresses',
  templateUrl: './addresses.component.html',
  styleUrl: './addresses.component.css',
})
export class AddressesComponent implements OnInit {
  addresses: ShippingAddress[] = [];
  selectedAddressId?: number;
  editingId?: number;
  formOpen = false;
  loading = true;
  busy = false;
  loadFailed = false;
  actionFailed = false;
  message = '';
  private readonly destroyRef = inject(DestroyRef);
  private readonly injector = inject(Injector);
  private readonly element: ElementRef<HTMLElement> = inject(ElementRef);
  draft: ShippingAddressRequest = this.empty();

  constructor(
    private api: ApiService,
    private changeDetector: ChangeDetectorRef,
    private i18n: I18nService,
  ) {}

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading = true;
    this.loadFailed = false;
    forkJoin({ addresses: this.api.getAddresses(), selected: this.api.selectedAddress() })
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.loading = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({
        next: ({ addresses, selected }) => {
          this.addresses = addresses;
          this.selectedAddressId = selected.addressId;
        },
        error: () => (this.loadFailed = true),
      });
  }

  startCreate(): void {
    this.editingId = undefined;
    this.draft = this.empty();
    this.openForm();
  }

  save(): void {
    if (!this.formOpen || this.busy) return;
    const request =
      this.editingId !== undefined
        ? this.api.updateAddress(this.editingId, this.draft)
        : this.api.createAddress(this.draft);
    this.runAction(request, () => {
      this.cancel();
      this.message = this.i18n.t('addressSaved');
      this.reload();
    });
  }

  edit({ id, ...address }: ShippingAddress): void {
    this.editingId = id;
    this.draft = address;
    this.openForm();
  }

  cancel(): void {
    this.formOpen = false;
    this.editingId = undefined;
    this.actionFailed = false;
    this.focusAfterRender('#add-shipping-address');
  }

  makeDefault(id: number): void {
    this.runAction(this.api.setDefaultAddress(id), () => this.reload());
  }

  select(id: number): void {
    this.runAction(this.api.selectAddress(id), () => {
      this.selectedAddressId = id;
      this.message = this.i18n.t('checkoutAddressSelected');
    });
  }

  remove(id: number): void {
    this.runAction(this.api.deleteAddress(id), () => {
      if (this.editingId === id) this.cancel();
      this.reload();
    });
  }

  private openForm(): void {
    this.formOpen = true;
    this.actionFailed = false;
    this.message = '';
    this.focusAfterRender('#address-label');
  }

  private focusAfterRender(selector: string): void {
    afterNextRender(() => this.element.nativeElement.querySelector<HTMLElement>(selector)?.focus(), {
      injector: this.injector,
    });
  }

  private runAction(request: Observable<unknown>, onSuccess: () => void): void {
    if (this.busy) return;
    this.busy = true;
    this.actionFailed = false;
    this.message = '';
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => {
          this.busy = false;
          this.changeDetector.markForCheck();
        }),
      )
      .subscribe({ next: onSuccess, error: () => (this.actionFailed = true) });
  }

  private empty(): ShippingAddressRequest {
    return {
      recipientName: '',
      phone: '',
      countryCode: 'CN',
      stateProvince: '',
      city: '',
      district: '',
      addressLine1: '',
      addressLine2: '',
      postalCode: '',
      label: 'Home',
      isDefault: false,
    };
  }
}
