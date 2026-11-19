import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { RouterTestingModule } from '@angular/router/testing';
import { of } from 'rxjs';

import { DashboardComponent } from './dashboard.component';
import { TranslatePipe } from '../pipes/translate.pipe';
import { ApiService } from '../services/api.service';

describe('DashboardComponent', () => {
  let component: DashboardComponent;
  let fixture: ComponentFixture<DashboardComponent>;
  let search: jasmine.Spy;

  beforeEach(async () => {
    search = jasmine.createSpy('searchProducts').and.returnValue(of({ products: [], total: 14, page: 1, size: 12 }));
    await TestBed.configureTestingModule({
      imports: [RouterTestingModule, FormsModule],
      declarations: [DashboardComponent, TranslatePipe],
      providers: [{ provide: ApiService, useValue: { searchProducts: search, publicStores: () => of([]) } }],
    }).compileComponents();

    fixture = TestBed.createComponent(DashboardComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('uses the server search total and effective page', () => {
    expect(search).toHaveBeenCalledWith({ q: '', sort: 'default', page: 0, size: 12 });
    expect(component.totalPages).toBe(2);
    expect(component.page).toBe(1);
  });
});
