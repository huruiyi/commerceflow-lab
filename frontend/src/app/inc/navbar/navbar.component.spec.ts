import { ComponentFixture, TestBed } from '@angular/core/testing';
import { RouterTestingModule } from '@angular/router/testing';

import { NavbarComponent } from './navbar.component';
import { TranslatePipe } from '../../pipes/translate.pipe';
import { TrdatePipe } from '../../pipes/trdate.pipe';
import { ApiService } from '../../services/api.service';
import { of } from 'rxjs';

describe('NavbarComponent', () => {
  let component: NavbarComponent;
  let fixture: ComponentFixture<NavbarComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [RouterTestingModule],
      declarations: [NavbarComponent, TranslatePipe, TrdatePipe],
      providers: [{provide: ApiService, useValue: {notifications: () => of([]), markNotificationRead: () => of(void 0), markAllNotificationsRead: () => of(void 0)}}]
    })
    .compileComponents();

    fixture = TestBed.createComponent(NavbarComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
