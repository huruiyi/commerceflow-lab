import {ComponentFixture, TestBed} from '@angular/core/testing';
import {RouterTestingModule} from '@angular/router/testing';

import {NavbarComponent} from './navbar.component';
import {TranslatePipe} from '../../pipes/translate.pipe';
import {TrdatePipe} from '../../pipes/trdate.pipe';
import {ApiService} from '../../services/api.service';
import {of} from 'rxjs';

describe('NavbarComponent', () => {
  let component: NavbarComponent;
  let fixture: ComponentFixture<NavbarComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RouterTestingModule],
      declarations: [NavbarComponent, TranslatePipe, TrdatePipe],
      providers: [{provide: ApiService,
        useValue: {
          notifications: () => of([]),
          markNotificationRead: () => of(void 0),
          markAllNotificationsRead: () => of(void 0)
        }
      }]
    })
      .compileComponents();

    fixture = TestBed.createComponent(NavbarComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('closes notifications from escape, outside clicks and notification selection', () => {
    component.notificationOpen = true;
    component.onEscape();
    expect(component.notificationOpen).toBeFalse();

    component.notificationOpen = true;
    component.onDocumentClick(new MouseEvent('click'));
    expect(component.notificationOpen).toBeFalse();

    component.notificationOpen = true;
    component.markNotificationRead({
      id: 1,
      notificationType: 'ORDER_CANCELLED',
      title: 'Order cancelled',
      message: 'Customer changed mind',
      status: 'READ',
      createdAt: '2026-11-14T00:16:00Z'
    });
    expect(component.notificationOpen).toBeFalse();
  });
});
