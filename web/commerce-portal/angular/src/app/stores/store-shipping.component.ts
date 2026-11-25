import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { finalize } from 'rxjs';
import { ApiService, ShippingProvinceOption, ShippingRate, ShippingTemplate } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

@Component({
  selector: 'app-store-shipping',
  imports: [FormsModule, HlmAlertImports, HlmButton],
  templateUrl: './store-shipping.component.html',
  styleUrl: './store-shipping.component.css',
})
export class StoreShippingComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroyRef = inject(DestroyRef);
  readonly i18n = inject(I18nService);
  shipping?: ShippingTemplate;
  shippingDraft?: ShippingTemplate;
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

  setCountry(region: ShippingRate, value: string): void {
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

  editShipping(): void {
    if (!this.shipping || this.shippingBusy()) return;
    this.shippingDraft = {
      defaultFee: this.shipping.defaultFee,
      regions: this.shipping.regions.map((region) => ({ ...region })),
    };
    this.shippingError.set('');
    this.shippingSaved.set(false);
    this.loadProvinces();
  }

  cancelShipping(): void {
    this.shippingDraft = undefined;
    this.shippingError.set('');
  }

  trimShippingLeadingZeros(input: HTMLInputElement): void {
    const amount = input.value.replace(/^0+(?=\d)/u, '');
    if (amount !== input.value) input.value = amount;
  }

  saveShipping(template: ShippingTemplate): void {
    if (this.shippingBusy()) return;
    this.shippingBusy.set(true);
    this.shippingError.set('');
    this.shippingSaved.set(false);
    this.api
      .saveShippingTemplate(template)
      .pipe(
        takeUntilDestroyed(this.destroyRef),
        finalize(() => this.shippingBusy.set(false)),
      )
      .subscribe({
        next: (updated) => {
          this.shipping = updated;
          this.shippingDraft = undefined;
          this.shippingSaved.set(true);
        },
        error: () => this.shippingError.set('shippingSaveFailed'),
      });
  }

  ngOnInit(): void {
    this.loadShipping();
  }
}
