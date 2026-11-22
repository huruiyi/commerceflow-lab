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

@Component({
  standalone: false,
  selector: 'app-navbar',
  templateUrl: './navbar.component.html',
  styleUrls: ['./navbar.component.css'],
})
export class NavbarComponent implements OnInit {
  searchText = '';
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
    this.notificationOpen = !this.notificationOpen;
    if (this.notificationOpen) this.loadNotifications();
  }

  closeNotifications(): void {
    this.notificationOpen = false;
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: MouseEvent): void {
    if (!this.notificationOpen) return;
    const menu = this.elementRef.nativeElement.querySelector('.notification-menu');
    if (!menu?.contains(event.target as Node)) this.closeNotifications();
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    this.closeNotifications();
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

  get isProductPage(): boolean {
    return this.router.url.startsWith('/dashboard');
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

  search(): void {
    const query = this.searchText.trim();
    const currentSort = this.router.parseUrl(this.router.url).queryParams['sort'];
    void this.router.navigate(['/dashboard'], { queryParams: { q: query || null, sort: currentSort || null } });
  }
}
