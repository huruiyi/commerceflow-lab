import { ChangeDetectorRef, Component, DestroyRef, ElementRef, HostListener, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, forkJoin, Observable, of, Subscription } from 'rxjs';
import { AccountService } from 'src/app/services/account.service';
import { UserProfile } from 'src/model/IUserLogin';
import { I18nService } from '../../services/i18n.service';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { CustomerNotification } from 'src/model/INotification';
import { notificationOrderSection } from 'src/model/IPurchaseOrder';
import { invoiceRoutes } from '../../invoices/invoice-api.service';

interface PortalLink {
  path: string;
  label: string;
  icon: string;
}

const roleLinks: Record<string, PortalLink[]> = {
  CUSTOMER: [
    { path: '/cart', label: 'cart', icon: 'cart3' },
    { path: '/orders', label: 'orders', icon: 'receipt' },
    { path: '/coupons', label: 'couponsHeading', icon: 'ticket-perforated' },
    { path: '/addresses', label: 'addresses', icon: 'geo-alt' },
  ],
  MERCHANT: [
    { path: '/merchant/orders', label: 'fulfillment', icon: 'truck' },
    { path: '/merchant/products', label: 'merchantProducts', icon: 'box-seam' },
    { path: '/merchant/inventory', label: 'inventory', icon: 'boxes' },
    { path: '/merchant/return-work-orders', label: 'returnWorkOrders', icon: 'arrow-return-left' },
    { path: '/merchant/settlements', label: 'merchantSettlement', icon: 'wallet2' },
    { path: '/merchant/payment-ledger-reports', label: 'ledgerHeading', icon: 'bar-chart' },
    { path: '/merchant/coupons', label: 'couponMerchantHeading', icon: 'ticket-perforated' },
    { path: '/merchant/store', label: 'ownStore', icon: 'shop' },
  ],
  ADMIN: [
    { path: '/admin/stores', label: 'storeReview', icon: 'shop' },
    { path: '/admin/inventory-reconciliation', label: 'warehouseReconciliation', icon: 'boxes' },
    { path: '/admin/exchanges', label: 'platformExchanges', icon: 'arrow-left-right' },
    { path: '/admin/payment-ledger-reports', label: 'ledgerHeading', icon: 'bar-chart' },
    { path: '/users', label: 'users', icon: 'people' },
    { path: '/admin/scheduling', label: 'scheduling', icon: 'clock' },
    { path: '/admin/logistics-callbacks', label: 'callbackGovernance', icon: 'arrow-repeat' },
    { path: '/admin/notification-deliveries', label: 'notificationRecovery', icon: 'bell' },
  ],
};

@Component({
  standalone: false,
  selector: 'app-navbar',
  templateUrl: './navbar.component.html',
  styleUrls: ['./navbar.component.css'],
})
export class NavbarComponent implements OnInit {
  readonly catalogLink: PortalLink = { path: '/dashboard', label: 'products', icon: 'grid' };
  readonly primaryLinkCount = 4;
  navigationOpen = false;
  moreNavigationOpen = false;
  notifications: CustomerNotification[] = [];
  unreadNotifications = 0;
  notificationOpen = false;
  notificationUnavailable = false;
  notificationReadFailed = false;
  notificationReadPending = false;
  private notificationLoad?: Subscription;
  bilgiler: UserProfile = {
    id: 0,
    firstName: '',
    lastName: '',
    email: '',
    phone: '',
    role: '',
  };

  constructor(
    private accountService: AccountService,
    public i18n: I18nService,
    private router: Router,
    private api: ApiService,
    private elementRef: ElementRef<HTMLElement>,
    private changeDetector: ChangeDetectorRef,
    private destroyRef: DestroyRef,
  ) {
    const userInfo = this.accountService.loginControl();

    if (userInfo) {
      this.bilgiler = userInfo;
    }
  }

  get invoiceRoute(): string | null {
    const profile = this.accountService.userProfile();
    if (!profile?.invoiceEnabled) return null;
    if (profile.role === 'CUSTOMER') return invoiceRoutes.customer;
    if (profile.role === 'MERCHANT') return invoiceRoutes.merchant;
    return profile.role === 'ADMIN' && profile.invoiceOperations ? invoiceRoutes.platform : null;
  }

  get navigationLinks(): PortalLink[] {
    const links = [this.catalogLink, ...(roleLinks[this.bilgiler.role] ?? [])];
    const invoicePath = this.invoiceRoute;
    if (invoicePath) links.push({ path: invoicePath, label: 'invoiceNav', icon: 'file-earmark-text' });
    return links;
  }

  closeNavigation(): void {
    this.navigationOpen = false;
    this.moreNavigationOpen = false;
  }

  toggleNavigation(): void {
    this.navigationOpen = !this.navigationOpen;
    this.moreNavigationOpen = false;
    this.closeNotifications();
  }

  ngOnInit(): void {
    if (this.bilgiler.role === 'CUSTOMER') this.loadNotifications();
  }

  loadNotifications(): void {
    if (this.notificationReadPending) return;
    this.notificationLoad?.unsubscribe();
    this.notificationLoad = forkJoin({
      notifications: this.api.notifications(),
      unread: this.api.unreadNotificationCount(),
    })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: ({ notifications, unread }) => {
          this.notificationUnavailable = false;
          this.notifications = notifications;
          this.unreadNotifications = unread.count;
          this.changeDetector.markForCheck();
        },
        error: () => {
          this.notificationUnavailable = true;
          this.changeDetector.markForCheck();
        },
      });
  }

  toggleNotifications(): void {
    this.closeNavigation();
    this.notificationOpen = !this.notificationOpen;
    if (this.notificationOpen) this.loadNotifications();
  }

  closeNotifications(): void {
    this.notificationOpen = false;
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    const host = this.elementRef.nativeElement;
    const target = event.target as Node;
    if (this.notificationOpen && !host.querySelector('.notification-menu')?.contains(target)) {
      this.closeNotifications();
    }
    if (this.moreNavigationOpen && !host.querySelector('.navigation-more')?.contains(target)) {
      this.moreNavigationOpen = false;
    }
    if (this.navigationOpen && !host.contains(target)) this.closeNavigation();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeNotifications();
    const host = this.elementRef.nativeElement;
    if (this.moreNavigationOpen) host.querySelector<HTMLButtonElement>('.navigation-more-toggle')?.focus();
    if (this.navigationOpen) host.querySelector<HTMLButtonElement>('.navigation-toggle')?.focus();
    this.closeNavigation();
  }

  openNotification(notification: CustomerNotification): void {
    if (notification.status === 'UNREAD') {
      this.updateNotificationRead(this.api.markNotificationRead(notification.eventId));
    }
    const id = notification.orderId;
    if (id && Number.isSafeInteger(id) && id > 0) {
      this.closeNotifications();
      void this.router.navigate(['/orders', id], { fragment: notificationOrderSection(notification.notificationType) });
    }
  }

  markAllNotificationsRead(): void {
    this.updateNotificationRead(this.api.markAllNotificationsRead());
  }

  private updateNotificationRead(request: Observable<void>): void {
    if (this.notificationReadPending) return;
    this.notificationLoad?.unsubscribe();
    this.notificationReadPending = true;
    this.notificationReadFailed = false;
    request
      .pipe(
        catchError(() => {
          this.notificationReadFailed = true;
          return of(void 0);
        }),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe(() => {
        this.notificationReadPending = false;
        this.loadNotifications();
        this.changeDetector.markForCheck();
      });
  }

  logout(): void {
    this.accountService.logout();
  }

  get accountDisplayName(): string {
    if (this.bilgiler.email.endsWith('@example.com') && this.bilgiler.firstName.toLowerCase() === 'demo') {
      return this.i18n.t(
        this.bilgiler.role === 'CUSTOMER'
          ? 'demoCustomerName'
          : this.bilgiler.role === 'MERCHANT'
            ? 'demoMerchantName'
            : 'demoAdminName',
      );
    }
    return (
      [this.bilgiler.firstName, this.bilgiler.lastName].filter(Boolean).join(' ') ||
      this.bilgiler.email ||
      this.i18n.t('account')
    );
  }

  get accountInitial(): string {
    return this.accountDisplayName.trim().slice(0, 1).toUpperCase() || '?';
  }
}
