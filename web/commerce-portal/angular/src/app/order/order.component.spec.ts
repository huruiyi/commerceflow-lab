import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { OrderComponent } from './order.component';
import { TranslatePipe } from '../pipes/translate.pipe';
import { TrcurrencyPipe } from '../pipes/trcurrency.pipe';
import { TrdatePipe } from '../pipes/trdate.pipe';
import { ApiService } from '../services/api.service';

describe('OrderComponent', () => {
  let component: OrderComponent;
  let fixture: ComponentFixture<OrderComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [OrderComponent, TranslatePipe, TrcurrencyPipe, TrdatePipe],
      providers: [
        {
          provide: ApiService,
          useValue: {
            getOrders: () => of([]),
            allProducts: () => of([]),
            productAvailability: () => of({}),
            getAddresses: () => of([]),
            selectedAddress: () => of({}),
          },
        },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(OrderComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
