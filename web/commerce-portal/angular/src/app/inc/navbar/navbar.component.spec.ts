import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';
import { Router } from '@angular/router';

import { NavbarComponent } from './navbar.component';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TrdatePipe } from '../../pipes/trdate.pipe';
import { ApiService } from '../../services/api.service';
import { of, Subject, throwError } from 'rxjs';

describe('NavbarComponent', () => {
  let component: NavbarComponent;
  let fixture: ComponentFixture<NavbarComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RouterTestingModule],
      declarations: [NavbarComponent, TranslatePipe, TrdatePipe],
      providers: [
        {
          provide: ApiService,
          useValue: {
            notifications: () => of([]),
            unreadNotificationCount: () => of({ count: 0 }),
            markNotificationRead: () => of(void 0),
            markAllNotificationsRead: () => of(void 0),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(NavbarComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('closes notifications from escape, outside clicks and notification selection', () => {
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    component.notificationOpen = true;
    component.onEscape();
    expect(component.notificationOpen).toBeFalse();

    component.notificationOpen = true;
    component.onDocumentClick(new MouseEvent('click'));
    expect(component.notificationOpen).toBeFalse();

    component.notificationOpen = true;
    component.openNotification({
      id: 1,
      eventId: 'notification-event-1',
      orderId: 42,
      notificationType: 'ORDER_CANCELLED',
      title: 'Order cancelled',
      message: 'Customer changed mind',
      status: 'READ',
      createdAt: '2026-11-14T00:16:00Z',
    });
    expect(component.notificationOpen).toBeFalse();
    expect(navigate).toHaveBeenCalledOnceWith(['/orders', 42], { fragment: 'cancellation' });
  });

  it('opens the order after a failed read and keeps general notifications in the panel', () => {
    const navigate = spyOn(TestBed.inject(Router), 'navigate').and.resolveTo(true);
    spyOn(TestBed.inject(ApiService), 'markNotificationRead').and.returnValue(throwError(() => new Error('offline')));
    component.notificationOpen = true;
    const notification = {
      id: 2,
      eventId: 'notification-event-2',
      notificationType: 'PAYMENT_SUCCEEDED',
      title: 'Payment received',
      message: 'Order payment succeeded',
      status: 'UNREAD' as const,
      createdAt: '2026-11-14T00:16:00Z',
    };
    component.openNotification(notification);
    expect(component.notificationOpen).toBeTrue();
    expect(navigate).not.toHaveBeenCalled();
    component.openNotification({ ...notification, orderId: 43 });
    expect(component.notificationReadFailed).toBeTrue();
    expect(navigate).toHaveBeenCalledOnceWith(['/orders', 43], { fragment: 'payment' });
  });

  it('ignores an older count and refreshes after marking all read', () => {
    const api = TestBed.inject(ApiService);
    const oldCount = new Subject<{ count: number }>();
    const markAll = new Subject<void>();
    const count = spyOn(api, 'unreadNotificationCount').and.returnValues(of({ count: 26 }), oldCount, of({ count: 1 }));
    spyOn(api, 'markAllNotificationsRead').and.returnValue(markAll);

    component.loadNotifications();
    expect(component.unreadNotifications).toBe(26);
    component.loadNotifications();
    component.markAllNotificationsRead();
    component.loadNotifications();
    expect(count).toHaveBeenCalledTimes(2);

    markAll.next();
    markAll.complete();
    oldCount.next({ count: 26 });
    oldCount.complete();

    expect(count).toHaveBeenCalledTimes(3);
    expect(component.unreadNotifications).toBe(1);
  });

  it('reports a failed mark-all, keeps the count and reconciles on retry', () => {
    const api = TestBed.inject(ApiService);
    spyOn(api, 'unreadNotificationCount').and.returnValues(of({ count: 26 }), of({ count: 26 }), of({ count: 0 }));
    spyOn(api, 'markAllNotificationsRead').and.returnValues(
      throwError(() => new Error('offline')),
      of(void 0),
    );

    component.loadNotifications();
    component.markAllNotificationsRead();
    expect(component.notificationReadFailed).toBeTrue();
    expect(component.unreadNotifications).toBe(26);

    component.markAllNotificationsRead();
    expect(component.notificationReadFailed).toBeFalse();
    expect(component.unreadNotifications).toBe(0);
  });
});
