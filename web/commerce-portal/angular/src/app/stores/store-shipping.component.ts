import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { finalize, Observable } from 'rxjs';
import {
  ApiService,
  ShippingProvinceOption,
  ShippingRate,
  ShippingRegionKey,
  ShippingTemplate,
} from '../services/api.service';
import { I18nService } from '../services/i18n.service';

interface ShippingDraft {
  original?: ShippingRate;
  rate: ShippingRegionKey & { fee: number | null };
}

@Component({
  selector: 'app-store-shipping',
  imports: [FormsModule, HlmAlertImports, HlmButton, NgTemplateOutlet],
  templateUrl: './store-shipping.component.html',
  styleUrl: './store-shipping.component.css',
})
export class StoreShippingComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly i18n = inject(I18nService);
  shipping?: ShippingTemplate;
  draft?: ShippingDraft;
  defaultDraft: number | null | undefined;
  pendingDelete?: ShippingRate;
  readonly shippingBusy = signal(false);
  readonly shippingError = signal('');
  readonly shippingSaved = signal(false);
  readonly provinces = signal<ShippingProvinceOption[]>([]);
  readonly provincesLoading = signal(false);
  readonly provincesFailed = signal(false);

  loadProvinces(): void {
    if (this.provincesLoading() || this.provinces().length) return;
    this.provincesLoading.set(true);
    this.provincesFailed.set(false);
    this.api
      .shippingProvinces()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.provincesLoading.set(false)),
      )
      .subscribe({
        next: (provinces) => {
          this.provinces.set(provinces);
          this.provincesFailed.set(provinces.length === 0);
        },
        error: () => this.provincesFailed.set(true),
      });
  }

  isDomestic(countryCode: string): boolean {
    return countryCode.trim().toUpperCase() === 'CN';
  }

  setCountry(region: ShippingRegionKey, value: string): void {
    if (region.countryCode.trim().toUpperCase() !== value.trim().toUpperCase()) region.stateProvince = '';
    region.countryCode = value;
  }

  isKnownProvince(name: string): boolean {
    return this.provinces().some((province) => province.name === name);
  }

  formatFee(amount: number): string {
    return new Intl.NumberFormat(this.i18n.locale(), { style: 'currency', currency: 'CNY' }).format(amount);
  }

  loadShipping(): void {
    if (this.shippingBusy()) return;
    this.shippingBusy.set(true);
    this.shippingError.set('');
    this.api
      .shippingTemplate()
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.shippingBusy.set(false)),
      )
      .subscribe({
        next: (template) => (this.shipping = template),
        error: () => this.shippingError.set('requestFailed'),
      });
  }

  editRegion(region?: ShippingRate): void {
    this.draft = { original: region, rate: region ? { ...region } : { countryCode: 'CN', stateProvince: '', fee: 0 } };
    this.shippingError.set('');
    this.shippingSaved.set(false);
    this.loadProvinces();
  }

  editDefault(fee: number): void {
    this.defaultDraft = fee;
    this.shippingError.set('');
    this.shippingSaved.set(false);
  }

  cancelEdit(): void {
    this.draft = undefined;
    this.defaultDraft = undefined;
    this.pendingDelete = undefined;
    this.shippingError.set('');
  }

  trimShippingLeadingZeros(input: HTMLInputElement): void {
    const amount = input.value.replace(/^0+(?=\d)/u, '');
    if (amount !== input.value) input.value = amount;
  }

  validFee(fee: number | null): fee is number {
    return fee !== null && Number.isFinite(fee) && fee >= 0 && fee <= 9999999999.99 && Number(fee.toFixed(2)) === fee;
  }

  saveDefault(fee: number | null): void {
    if (this.validFee(fee)) this.mutate(this.api.saveDefaultShippingRate(fee));
  }

  saveRegion(draft: ShippingDraft): void {
    const { countryCode, stateProvince, fee } = draft.rate;
    if (!stateProvince.trim() || !this.validFee(fee)) return;
    const rate = { countryCode, stateProvince, fee };
    this.mutate(draft.original ? this.api.editShippingRate(draft.original, rate) : this.api.addShippingRate(rate));
  }

  toggleRegion(region: ShippingRate): void {
    this.mutate(this.api.setShippingRateEnabled(region, !region.enabled));
  }

  deleteRegion(region: ShippingRate): void {
    this.mutate(this.api.deleteShippingRate(region));
  }

  private mutate(request: Observable<ShippingTemplate>): void {
    if (this.shippingBusy()) return;
    this.shippingBusy.set(true);
    this.shippingError.set('');
    this.shippingSaved.set(false);
    request
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.shippingBusy.set(false)),
      )
      .subscribe({
        next: (updated) => {
          this.shipping = updated;
          this.cancelEdit();
          this.shippingSaved.set(true);
        },
        error: () => this.shippingError.set('shippingSaveFailed'),
      });
  }

  ngOnInit(): void {
    this.loadShipping();
  }
}
