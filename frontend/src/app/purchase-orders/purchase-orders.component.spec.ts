import { ComponentFixture,TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { PurchaseOrdersComponent } from './purchase-orders.component';
import { ApiService } from '../services/api.service';
describe('PurchaseOrdersComponent',()=>{let fixture:ComponentFixture<PurchaseOrdersComponent>;beforeEach(async()=>{await TestBed.configureTestingModule({declarations:[PurchaseOrdersComponent],providers:[{provide:ApiService,useValue:{purchaseOrders:()=>of([])}}]}).compileComponents();fixture=TestBed.createComponent(PurchaseOrdersComponent);fixture.detectChanges();});it('loads an empty order list',()=>expect(fixture.componentInstance.orders).toEqual([]));});
