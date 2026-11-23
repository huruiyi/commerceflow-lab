import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { of } from 'rxjs';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { ApiService } from '../services/api.service';

import { UsersComponent } from './users.component';
import { TranslatePipe } from '../pipes/translate.pipe';

describe('UsersComponent', () => {
  let component: UsersComponent;
  let fixture: ComponentFixture<UsersComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [FormsModule, ListPaginationComponent],
      providers: [{ provide: ApiService, useValue: { getUsers: () => of({ rows: [], total: 0, page: 0, size: 10 }) } }],
      declarations: [UsersComponent, TranslatePipe],
    }).compileComponents();

    fixture = TestBed.createComponent(UsersComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
