import { NgModule } from '@angular/core';
import { RouterModule, Routes } from '@angular/router';
import { AuthGuard } from './auth.guard';
import { DashboardComponent } from './dashboard/dashboard.component';
import { ErrorpageComponent } from './errorpage/errorpage.component';
import { LoginComponent } from './login/login.component';
import { CouponsComponent } from './coupons/coupons.component';
import { StoresComponent } from './stores/stores.component';
import { SettlementsComponent } from './stores/settlements.component';
import { OrderComponent } from './order/order.component';
import { UsersComponent } from './users/users.component';
import { AddressesComponent } from './addresses/addresses.component';
import { PurchaseOrdersComponent } from './purchase-orders/purchase-orders.component';
import { MerchantOrdersComponent } from './merchant-orders/merchant-orders.component';
import { MerchantReturnWorkOrdersComponent } from './merchant-return-work-orders/merchant-return-work-orders.component';
import { ProfileComponent } from './profile/profile.component';
import { SchedulingAdminComponent } from './scheduling-admin/scheduling-admin.component';
import { LogisticsCallbackAdminComponent } from './logistics-callback-admin/logistics-callback-admin.component';
import { MerchantInventoryComponent } from './merchant-inventory/merchant-inventory.component';
import { MerchantCatalogComponent } from './merchant-catalog/merchant-catalog.component';
import { PaymentLedgerReportsComponent } from './payment-ledger-reports/payment-ledger-reports.component';
import { InventoryReconciliationAdminComponent } from './inventory-reconciliation/inventory-reconciliation.component';

const routes: Routes = [
  { path: '', component: LoginComponent }, //LoginGuard
  { path: 'dashboard', component: DashboardComponent, canActivate: [AuthGuard] },
  { path: 'stores/:id', component: StoresComponent, data: { mode: 'public' } },
  {
    path: 'merchant/store',
    component: StoresComponent,
    canActivate: [AuthGuard],
    data: { mode: 'merchant', roles: ['MERCHANT'] },
  },
  {
    path: 'admin/stores',
    component: StoresComponent,
    canActivate: [AuthGuard],
    data: { mode: 'admin', roles: ['ADMIN'] },
  },
  {
    path: 'admin/stores/:id/settlements',
    component: SettlementsComponent,
    canActivate: [AuthGuard],
    data: { admin: true, roles: ['ADMIN'] },
  },
  {
    path: 'merchant/settlements',
    component: SettlementsComponent,
    canActivate: [AuthGuard],
    data: { roles: ['MERCHANT'] },
  },
  { path: 'coupons', component: CouponsComponent, canActivate: [AuthGuard], data: { roles: ['CUSTOMER'] } },
  {
    path: 'merchant/coupons',
    component: CouponsComponent,
    canActivate: [AuthGuard],
    data: { roles: ['MERCHANT'], merchant: true },
  },
  { path: 'cart', component: OrderComponent, canActivate: [AuthGuard], data: { roles: ['CUSTOMER'] } },
  { path: 'addresses', component: AddressesComponent, canActivate: [AuthGuard], data: { roles: ['CUSTOMER'] } },
  { path: 'orders', component: PurchaseOrdersComponent, canActivate: [AuthGuard], data: { roles: ['CUSTOMER'] } },
  { path: 'orders/:id', component: PurchaseOrdersComponent, canActivate: [AuthGuard], data: { roles: ['CUSTOMER'] } },
  {
    path: 'merchant/orders',
    component: MerchantOrdersComponent,
    canActivate: [AuthGuard],
    data: { roles: ['MERCHANT'] },
  },
  {
    path: 'merchant/return-work-orders',
    component: MerchantReturnWorkOrdersComponent,
    canActivate: [AuthGuard],
    data: { roles: ['MERCHANT'] },
  },
  {
    path: 'merchant/inventory',
    component: MerchantInventoryComponent,
    canActivate: [AuthGuard],
    data: { roles: ['MERCHANT'] },
  },
  {
    path: 'merchant/products',
    component: MerchantCatalogComponent,
    canActivate: [AuthGuard],
    data: { roles: ['MERCHANT'] },
  },
  { path: 'profile', component: ProfileComponent, canActivate: [AuthGuard] },
  { path: 'users', component: UsersComponent, canActivate: [AuthGuard], data: { roles: ['ADMIN'] } },
  {
    path: 'admin/scheduling',
    component: SchedulingAdminComponent,
    canActivate: [AuthGuard],
    data: { roles: ['ADMIN'] },
  },
  {
    path: 'admin/logistics-callbacks',
    component: LogisticsCallbackAdminComponent,
    canActivate: [AuthGuard],
    data: { roles: ['ADMIN'] },
  },
  {
    path: 'admin/inventory-reconciliation',
    component: InventoryReconciliationAdminComponent,
    canActivate: [AuthGuard],
    data: { roles: ['ADMIN'] },
  },
  {
    path: 'admin/payment-ledger-reports',
    component: PaymentLedgerReportsComponent,
    canActivate: [AuthGuard],
    data: { admin: true, roles: ['ADMIN'] },
  },
  {
    path: 'merchant/payment-ledger-reports',
    component: PaymentLedgerReportsComponent,
    canActivate: [AuthGuard],
    data: { roles: ['MERCHANT'] },
  },
  { path: '**', component: ErrorpageComponent },
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule],
})
export class AppRoutingModule {}
