import {ComponentFixture, TestBed} from '@angular/core/testing';

import {ErrorpageComponent} from './errorpage.component';
import {TranslatePipe} from '../pipes/translate.pipe';

describe('ErrorpageComponent', () => {
  let component: ErrorpageComponent;
  let fixture: ComponentFixture<ErrorpageComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      declarations: [ErrorpageComponent, TranslatePipe]
    })
      .compileComponents();

    fixture = TestBed.createComponent(ErrorpageComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });
});
