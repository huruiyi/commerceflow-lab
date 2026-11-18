import { HttpErrorResponse } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { of, throwError } from 'rxjs';
import { InventoryRecovery, WarehouseEvidenceContext } from 'src/model/IInventoryReconciliation';
import { TranslatePipe } from '../pipes/translate.pipe';
import { TrdatePipe } from '../pipes/trdate.pipe';
import { ApiService } from '../services/api.service';
import { InventoryReconciliationAdminComponent } from './inventory-reconciliation-admin.component';

describe('InventoryReconciliationAdminComponent', () => {
  const recovery: InventoryRecovery = {
    orderId: 81001,
    commandId: 'order:81001:reserve',
    commandType: 'RESERVE',
    attempts: 5,
    lastError: null,
    createdAt: '2025-06-03T00:00:00Z',
  };
  const context: WarehouseEvidenceContext = {
    orderId: recovery.orderId,
    orderNumber: 'ORD-WAREHOUSE-FIXTURE',
    status: 'COMPLETED',
    createdAt: '2025-06-03T00:00:00Z',
    deliveredAt: '2025-06-04T00:00:00Z',
    products: [
      { productId: 1, name: 'First product', quantity: 1 },
      { productId: 2, name: 'Second product', quantity: 2 },
    ],
  };
  let api: jasmine.SpyObj<ApiService>;
  let fixture: ComponentFixture<InventoryReconciliationAdminComponent>;

  beforeEach(async () => {
    api = jasmine.createSpyObj<ApiService>('ApiService', [
      'inventoryRecoveries',
      'warehouseEvidenceContext',
      'submitWarehouseEvidence',
    ]);
    api.inventoryRecoveries.and.returnValue(of([recovery]));
    api.warehouseEvidenceContext.and.returnValue(of(context));
    api.submitWarehouseEvidence.and.returnValue(of({ orderId: recovery.orderId, movementIds: [51, 52] }));
    await TestBed.configureTestingModule({
      declarations: [InventoryReconciliationAdminComponent, TranslatePipe, TrdatePipe],
      imports: [FormsModule],
      providers: [{ provide: ApiService, useValue: api }],
    }).compileComponents();
    fixture = TestBed.createComponent(InventoryReconciliationAdminComponent);
    fixture.detectChanges();
  });

  it('submits both dispositions using the order products and removes only the closed entry', () => {
    const component = fixture.componentInstance;
    const row: HTMLButtonElement = fixture.nativeElement.querySelector('.queue-row');
    row.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('ORD-WAREHOUSE-FIXTURE');
    const evidenceAt = '2025-06-04T11:30';
    const note = 'Verified warehouse evidence';
    Object.assign(component.drafts[0], {
      warehouseReference: 'WH:11:1',
      evidenceAt,
      note,
      mode: 'existing',
      bookedMovementId: 51,
      expectedAvailable: 100,
    });
    Object.assign(component.drafts[1], {
      warehouseReference: 'WH:11:2',
      evidenceAt,
      note,
      mode: 'outbound',
      expectedAvailable: 10,
      bookedMovementId: 99,
    });
    component.submit();
    fixture.detectChanges();
    const utc = new Date(evidenceAt).toISOString();
    expect(api.submitWarehouseEvidence).toHaveBeenCalledOnceWith(recovery.commandId, [
      { productId: 1, warehouseReference: 'WH:11:1', evidenceAt: utc, note, bookedMovementId: 51 },
      { productId: 2, warehouseReference: 'WH:11:2', evidenceAt: utc, note, expectedAvailable: 10 },
    ]);
    expect(fixture.nativeElement.querySelectorAll('.queue-row').length).toBe(0);
    expect(fixture.nativeElement.querySelector('.alert-success').textContent).toContain('51, 52');
  });

  it('keeps the order pending and preserves entered evidence when submission is rejected', () => {
    const component = fixture.componentInstance;
    component.open(recovery);
    component.submit();
    expect(api.submitWarehouseEvidence).not.toHaveBeenCalled();
    api.submitWarehouseEvidence.and.returnValue(
      throwError(() => new HttpErrorResponse({ status: 409, error: { message: 'Balance has changed' } })),
    );
    component.drafts.forEach((draft, index) =>
      Object.assign(draft, {
        warehouseReference: `WH:11:${index + 1}`,
        evidenceAt: '2025-06-04T11:30',
        note: 'Verified warehouse evidence',
        mode: 'outbound',
        expectedAvailable: 10,
      }),
    );
    component.submit();
    fixture.detectChanges();
    expect(component.recoveries).toEqual([recovery]);
    expect(component.drafts[0].warehouseReference).toBe('WH:11:1');
    expect(fixture.nativeElement.querySelector('[role="alert"]').textContent).toContain('Balance has changed');
  });
});
