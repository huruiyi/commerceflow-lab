import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { of } from 'rxjs';
import { AddressesComponent } from './addresses.component';
import { ApiService } from '../services/api.service';

describe('AddressesComponent', () => {
  let fixture: ComponentFixture<AddressesComponent>;
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [AddressesComponent], imports: [FormsModule],
      providers: [{provide: ApiService, useValue: {getAddresses: () => of([]), selectedAddress: () => of({})}}]
    }).compileComponents();
    fixture = TestBed.createComponent(AddressesComponent); fixture.detectChanges();
  });
  it('creates an empty address form', () => {
    expect(fixture.componentInstance).toBeTruthy();
    expect(fixture.componentInstance.addresses).toEqual([]);
  });
});
