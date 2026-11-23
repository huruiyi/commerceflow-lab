import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { provideRouter, RouterModule } from '@angular/router';
import { of } from 'rxjs';
import { AddressesComponent } from './addresses.component';
import { ApiService } from '../services/api.service';
import { TranslatePipe } from '../pipes/translate.pipe';

describe('AddressesComponent', () => {
  let fixture: ComponentFixture<AddressesComponent>;
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AddressesComponent, TranslatePipe],
      imports: [FormsModule, RouterModule],
      providers: [
        provideRouter([]),
        { provide: ApiService, useValue: { getAddresses: () => of([]), selectedAddress: () => of({}) } },
      ],
    }).compileComponents();
    fixture = TestBed.createComponent(AddressesComponent);
    fixture.detectChanges();
  });
  it('opens an empty form on demand and hides it when cancelled', () => {
    const view = fixture.nativeElement as HTMLElement;
    expect(view.querySelector('form')).toBeNull();
    view.querySelector<HTMLButtonElement>('#add-shipping-address')?.click();
    fixture.detectChanges();
    expect(view.querySelector('form')).not.toBeNull();
    expect(fixture.componentInstance.draft.recipientName).toBe('');
    view.querySelector<HTMLButtonElement>('form button[type=button]')?.click();
    fixture.detectChanges();
    expect(view.querySelector('form')).toBeNull();
  });
});
