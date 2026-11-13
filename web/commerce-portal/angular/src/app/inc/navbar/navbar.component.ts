import { Component, ElementRef, HostListener, OnInit } from '@angular/core';
import { AccountService } from 'src/app/services/account.service';
import { UserProfile } from 'src/model/IUserLogin';
import { I18nService } from '../../services/i18n.service';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { CustomerNotification } from 'src/model/INotification';

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
  ) {
    const userInfo = this.accountService.loginControl();

    if (userInfo) {
      this.bilgiler = userInfo;
    }
  }

  ngOnInit(): void {
    if (this.bilgiler.role === 'CUSTOMER') this.loadNotifications();
  }

  loadNotifications(): void {
    this.api.notifications().subscribe({
      next: (notifications) => {
        this.notifications = notifications;
        this.unreadNotifications = notifications.filter((item) => item.status === 'UNREAD').length;
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

  markNotificationRead(notification: CustomerNotification): void {
    this.closeNotifications();
    if (notification.status === 'READ') return;
    this.api.markNotificationRead(notification.id).subscribe({
      next: () => {
        notification.status = 'READ';
        this.unreadNotifications = Math.max(0, this.unreadNotifications - 1);
      },
    });
  }

  markAllNotificationsRead(): void {
    this.api.markAllNotificationsRead().subscribe({
      next: () => {
        this.notifications.forEach((item) => (item.status = 'READ'));
        this.unreadNotifications = 0;
      },
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
    void this.router.navigate(['/dashboard'], { queryParams: query ? { q: query } : {} });
  }
}
