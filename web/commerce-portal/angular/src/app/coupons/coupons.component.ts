import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { ApiService, CouponCampaign, CustomerCoupon } from '../services/api.service';

@Component({
  standalone: false,
  selector: 'app-coupons',
  templateUrl: './coupons.component.html',
})
export class CouponsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroy = inject(DestroyRef);
  readonly merchant = inject(ActivatedRoute).snapshot.data['merchant'] === true;
  readonly campaigns = signal<CouponCampaign[]>([]);
  readonly wallet = signal<CustomerCoupon[]>([]);
  readonly busy = signal(false);
  readonly error = signal(false);
  title = '';
  minimumAmount = 200;
  discountAmount = 30;
  supply = 100;
  expiresAt = '';

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.run(this.api.couponCampaigns(this.merchant), (items) => this.campaigns.set(items));
    if (!this.merchant) this.refreshWallet();
  }

  create(): void {
    this.run(
      this.api.createCouponCampaign({
        title: this.title.trim(),
        minimumAmount: this.minimumAmount,
        discountAmount: this.discountAmount,
        supply: this.supply,
        startsAt: new Date().toISOString(),
        expiresAt: new Date(this.expiresAt).toISOString(),
      }),
      (item) => {
        this.campaigns.update((items) => [item, ...items]);
        this.title = '';
      },
    );
  }

  disable(item: CouponCampaign): void {
    this.run(this.api.disableCouponCampaign(item.id), () =>
      this.campaigns.update((items) =>
        items.map((value) => (value.id === item.id ? { ...value, enabled: false } : value)),
      ),
    );
  }

  claim(item: CouponCampaign): void {
    this.run(this.api.claimCoupon(item.id), (coupon) => {
      this.wallet.update((items) => [coupon, ...items.filter((value) => value.id !== coupon.id)]);
    });
  }

  alreadyClaimed(id: string): boolean {
    return this.wallet().some((item) => item.campaignId === id);
  }

  private refreshWallet(): void {
    this.api
      .couponWallet()
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (items) => this.wallet.set(items),
        error: () => this.error.set(true),
      });
  }

  private run<T>(request: Observable<T>, success: (value: T) => void): void {
    if (this.busy()) return;
    this.error.set(false);
    this.busy.set(true);
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: success,
        error: () => this.error.set(true),
      });
  }
}
