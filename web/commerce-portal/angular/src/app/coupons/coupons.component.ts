import { Component, computed, DestroyRef, inject, OnInit, signal, WritableSignal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { ApiService, CouponCampaign, CustomerCoupon } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

interface WalletCoupon extends CustomerCoupon {
  discountAmount?: number;
}

type WalletState = CustomerCoupon['status'] | 'DISABLED';
type WalletFilter = WalletState | 'ALL';

const walletFilters: WalletFilter[] = ['ALL', 'AVAILABLE', 'HELD', 'USED', 'EXPIRED', 'DISABLED'];
const pageSize = 6;

function walletState(coupon: CustomerCoupon): WalletState {
  return coupon.status === 'AVAILABLE' && !coupon.enabled ? 'DISABLED' : coupon.status;
}

@Component({
  standalone: false,
  selector: 'app-coupons',
  templateUrl: './coupons.component.html',
  styleUrl: './coupons.component.css',
})
export class CouponsComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroy = inject(DestroyRef);
  readonly i18n = inject(I18nService);
  readonly merchant = inject(ActivatedRoute).snapshot.data['merchant'] === true;
  readonly campaigns = signal<CouponCampaign[]>([]);
  readonly wallet = signal<WalletCoupon[]>([]);
  readonly campaignsLoading = signal(false);
  readonly walletLoading = signal(false);
  readonly campaignsFailed = signal(false);
  readonly walletFailed = signal(false);
  readonly busy = signal(false);
  readonly error = signal(false);
  readonly activityNotice = signal('');
  readonly walletState = walletState;
  private readonly chosenWalletFilter = signal<WalletFilter | null>(null);
  private readonly requestedWalletPage = signal(0);
  private readonly requestedCampaignPage = signal(0);
  readonly walletCampaignIds = computed(() => new Set(this.wallet().map((coupon) => coupon.campaignId)));
  readonly walletCounts = computed(() => {
    const counts: Record<WalletFilter, number> = {
      ALL: this.wallet().length,
      AVAILABLE: 0,
      HELD: 0,
      USED: 0,
      EXPIRED: 0,
      DISABLED: 0,
    };
    for (const coupon of this.wallet()) counts[walletState(coupon)]++;
    return counts;
  });
  readonly walletFilters = computed(() =>
    walletFilters.filter(
      (filter) => filter !== 'DISABLED' || this.walletCounts().DISABLED > 0 || this.chosenWalletFilter() === filter,
    ),
  );
  readonly walletFilter = computed(
    () => this.chosenWalletFilter() ?? (this.walletCounts().AVAILABLE > 0 ? 'AVAILABLE' : 'ALL'),
  );
  readonly campaignPages = computed(() => Math.ceil(this.campaigns().length / pageSize));
  readonly campaignPage = computed(() => Math.min(this.requestedCampaignPage(), Math.max(0, this.campaignPages() - 1)));
  readonly campaignRows = computed(() => {
    const first = this.campaignPage() * pageSize;
    return this.campaigns().slice(first, first + pageSize);
  });
  private readonly matchingWallet = computed(() => {
    const filter = this.walletFilter();
    return filter === 'ALL' ? this.wallet() : this.wallet().filter((coupon) => walletState(coupon) === filter);
  });
  readonly walletPages = computed(() => Math.ceil(this.matchingWallet().length / pageSize));
  readonly walletPage = computed(() => Math.min(this.requestedWalletPage(), Math.max(0, this.walletPages() - 1)));
  readonly walletRows = computed(() => {
    const first = this.walletPage() * pageSize;
    return this.matchingWallet().slice(first, first + pageSize);
  });
  title = '';
  minimumAmount = 200;
  discountAmount = 30;
  supply = 100;
  expiresAt = '';

  ngOnInit(): void {
    this.loadCampaigns();
    if (!this.merchant) this.loadWallet();
  }

  campaignStatus(item: CouponCampaign): 'ACTIVE' | 'DISABLED' | 'EXPIRED' | 'SCHEDULED' | 'FULL' {
    if (!item.enabled) return 'DISABLED';
    const now = Date.now();
    if (Date.parse(item.expiresAt) <= now) return 'EXPIRED';
    if (item.startsAt && Date.parse(item.startsAt) > now) return 'SCHEDULED';
    return item.issued >= item.supply ? 'FULL' : 'ACTIVE';
  }

  loadCampaigns(): void {
    this.load(this.api.couponCampaigns(this.merchant), this.campaigns, this.campaignsLoading, this.campaignsFailed);
  }

  loadWallet(): void {
    this.load(this.api.couponWallet(), this.wallet, this.walletLoading, this.walletFailed);
  }

  filterWallet(filter: WalletFilter): void {
    this.chosenWalletFilter.set(filter);
    this.requestedWalletPage.set(0);
  }

  changeWalletPage(page: number): void {
    this.requestedWalletPage.set(page);
  }

  changeCampaignPage(page: number): void {
    this.requestedCampaignPage.set(page);
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
        this.changeCampaignPage(0);
        this.activityNotice.set(item.title);
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
      this.filterWallet(walletState(coupon));
      this.activityNotice.set(coupon.title);
    });
  }

  private load<T>(
    request: Observable<T[]>,
    items: WritableSignal<T[]>,
    loading: WritableSignal<boolean>,
    failed: WritableSignal<boolean>,
  ): void {
    if (loading() || this.busy()) return;
    failed.set(false);
    loading.set(true);
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => loading.set(false)),
      )
      .subscribe({
        next: (values) => items.set(values),
        error: () => failed.set(true),
      });
  }

  private run<T>(request: Observable<T>, success: (value: T) => void): void {
    if (this.busy()) return;
    this.error.set(false);
    this.activityNotice.set('');
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
