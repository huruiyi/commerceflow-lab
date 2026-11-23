import { CommonModule } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { provideRouter, RouterLink } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, Subject, throwError } from 'rxjs';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { TrcurrencyPipe } from '../pipes/trcurrency.pipe';
import { TrdatePipe } from '../pipes/trdate.pipe';
import { TranslatePipe } from '../pipes/translate.pipe';
import { ApiService, CouponCampaign, CustomerCoupon } from '../services/api.service';
import { CouponsComponent } from './coupons.component';

describe('CouponsComponent', () => {
  const campaign: CouponCampaign = {
    id: 'campaign',
    title: 'Learning supplies',
    minimumAmount: 200,
    discountAmount: 30,
    supply: 25,
    issued: 3,
    expiresAt: '2099-01-01T00:00:00Z',
    enabled: true,
  };
  let api: jasmine.SpyObj<ApiService>;

  function coupon(id: string, status: CustomerCoupon['status'], enabled = true) {
    return {
      id,
      campaignId: id,
      title: 'Coupon ' + id,
      minimumAmount: 200,
      discountAmount: 30,
      status,
      enabled,
      expiresAt: campaign.expiresAt,
    };
  }

  beforeEach(() => {
    localStorage.removeItem('commerceflow-locale');
    api = jasmine.createSpyObj<ApiService>('ApiService', [
      'couponCampaigns',
      'couponWallet',
      'claimCoupon',
      'createCouponCampaign',
      'disableCouponCampaign',
    ]);
    api.couponCampaigns.and.returnValue(of([campaign]));
    api.couponWallet.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [CommonModule, FormsModule, RouterLink, ListPaginationComponent],
      declarations: [CouponsComponent, TrcurrencyPipe, TrdatePipe, TranslatePipe],
      providers: [
        { provide: ApiService, useValue: api },
        provideRouter([
          { path: 'coupons', component: CouponsComponent },
          { path: 'merchant/coupons', component: CouponsComponent, data: { merchant: true } },
        ]),
      ],
    });
  });

  async function open(path = '/coupons') {
    const harness = await RouterTestingHarness.create();
    const component = await harness.navigateByUrl(path, CouponsComponent);
    return { harness, component, root: harness.routeNativeElement! };
  }

  it('starts with real available coupons and paginates every matching record', async () => {
    api.couponWallet.and.returnValue(
      of([...Array.from({ length: 13 }, (_, index) => coupon(String(index + 1), 'AVAILABLE')), coupon('used', 'USED')]),
    );
    const { harness, component, root } = await open();
    expect(component.walletFilter()).toBe('AVAILABLE');
    expect(component.walletPages()).toBe(3);
    expect(root.querySelectorAll('.coupon-list:not(.campaign-list) .coupon-row').length).toBe(6);
    (root.querySelector('nav[aria-label="Coupon wallet pages"] [aria-label="Page 3"]') as HTMLButtonElement).click();
    harness.detectChanges();
    expect(component.walletRows().map((item) => item.id)).toEqual(['13']);

    (root.querySelectorAll('.coupon-filter')[3] as HTMLButtonElement).click();
    harness.detectChanges();
    expect(component.walletFilter()).toBe('USED');
    expect(component.walletPage()).toBe(0);
    expect(root.querySelector('.coupon-status[data-state="USED"]')?.textContent?.trim()).toBe('Used');
  });

  it('shows history when none are usable and distinguishes held, expired and stopped coupons', async () => {
    api.couponWallet.and.returnValue(
      of([coupon('held', 'HELD'), coupon('expired', 'EXPIRED'), coupon('stopped', 'AVAILABLE', false)]),
    );
    const { component, root } = await open();
    expect(component.walletFilter()).toBe('ALL');
    expect(component.walletCounts().AVAILABLE).toBe(0);
    expect(Array.from(root.querySelectorAll('.coupon-status'), (badge) => badge.getAttribute('data-state'))).toEqual([
      'HELD',
      'EXPIRED',
      'DISABLED',
    ]);
  });

  it('reveals the claimed coupon in the wallet and prevents a second claim from the campaign list', async () => {
    const claim = new Subject<CustomerCoupon>();
    api.couponWallet.and.returnValue(of([coupon('used', 'USED')]));
    api.claimCoupon.and.returnValue(claim);
    const { harness, component, root } = await open();
    component.filterWallet('USED');
    (root.querySelector('.campaign-action button') as HTMLButtonElement).click();
    expect(api.claimCoupon).toHaveBeenCalledOnceWith(campaign.id);
    claim.next({ ...coupon('new', 'AVAILABLE'), campaignId: campaign.id });
    claim.complete();
    harness.detectChanges();
    expect(component.walletFilter()).toBe('AVAILABLE');
    expect(root.querySelector('.alert-success')?.textContent).toContain('Coupon new');
    expect(root.querySelector('.coupon-list:not(.campaign-list) h3')?.textContent?.trim()).toBe('Coupon new');
    expect(root.querySelector('.campaign-action button')).toBeNull();
    expect(root.querySelector('.campaign-action')?.textContent?.trim()).toBe('Claimed');
    expect(root.querySelector('a')?.getAttribute('href')).toBe('/cart');
  });

  it('keeps campaigns visible when loading the wallet fails and can retry the wallet independently', async () => {
    api.couponWallet.and.returnValues(
      throwError(() => new Error('unavailable')),
      of([]),
    );
    const { harness, root } = await open();
    expect(root.querySelector('[role="alert"]')?.textContent).toContain('Could not load your coupons');
    expect((root.querySelector('.campaign-action button') as HTMLButtonElement).disabled).toBeTrue();
    (root.querySelector('[role="alert"] button') as HTMLButtonElement).click();
    harness.detectChanges();
    expect(root.querySelector('[role="alert"]')).toBeNull();
    expect((root.querySelector('.campaign-action button') as HTMLButtonElement).disabled).toBeFalse();
    expect(api.couponCampaigns).toHaveBeenCalledOnceWith(false);
  });

  it('keeps merchant creation and campaign stopping available without loading a customer wallet', async () => {
    api.createCouponCampaign.and.returnValue(of({ ...campaign, id: 'new' }));
    api.disableCouponCampaign.and.returnValue(of(undefined));
    const { harness, component, root } = await open('/merchant/coupons');
    component.title = 'New campaign';
    component.expiresAt = '2099-01-01T00:00';
    component.create();
    harness.detectChanges();
    expect(component.campaignRows().map((item) => item.id)).toEqual(['new', 'campaign']);
    (root.querySelector('.campaign-action button') as HTMLButtonElement).click();
    harness.detectChanges();
    expect(api.disableCouponCampaign).toHaveBeenCalledOnceWith('new');
    expect(root.querySelector('.campaign-action .coupon-status')?.textContent?.trim()).toBe('Stopped');
    expect(api.couponWallet).not.toHaveBeenCalled();
  });
});
