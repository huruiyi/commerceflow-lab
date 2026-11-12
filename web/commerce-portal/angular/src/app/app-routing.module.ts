import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';
import {AuthGuard} from './auth.guard';
import {DashboardComponent} from './dashboard/dashboard.component';
import {ErrorpageComponent} from './errorpage/errorpage.component';
import {LoginComponent} from './login/login.component';
import {OrderComponent} from './order/order.component';
import {UsersComponent} from './users/users.component';
import {AddressesComponent} from './addresses/addresses.component';
import {PurchaseOrdersComponent} from './purchase-orders/purchase-orders.component';
import {MerchantOrdersComponent} from './merchant-orders/merchant-orders.component';
import {ProfileComponent} from './profile/profile.component';
import {SchedulingAdminComponent} from './scheduling-admin/scheduling-admin.component';
import {LogisticsCallbackAdminComponent} from './logistics-callback-admin/logistics-callback-admin.component';
import {MerchantInventoryComponent} from './merchant-inventory/merchant-inventory.component';

const routes: Routes = [
  {path: '', component: LoginComponent},  //LoginGuard
  {path: 'dashboard', component: DashboardComponent, canActivate: [AuthGuard]},
  {path: 'cart', component: OrderComponent, canActivate: [AuthGuard], data: {roles: ['CUSTOMER']}},
  {path: 'addresses', component: AddressesComponent, canActivate: [AuthGuard], data: {roles: ['CUSTOMER']}},
  {path: 'orders', component: PurchaseOrdersComponent, canActivate: [AuthGuard], data: {roles: ['CUSTOMER']}},
  {path: 'merchant/orders', component: MerchantOrdersComponent, canActivate: [AuthGuard], data: {roles: ['MERCHANT']}},
  {
    path: 'merchant/inventory',
    component: MerchantInventoryComponent,
    canActivate: [AuthGuard],
    data: {roles: ['MERCHANT']}
  },
  {path: 'profile', component: ProfileComponent, canActivate: [AuthGuard]},
  {path: 'users', component: UsersComponent, canActivate: [AuthGuard], data: {roles: ['ADMIN']}},
  {path: 'admin/scheduling', component: SchedulingAdminComponent, canActivate: [AuthGuard], data: {roles: ['ADMIN']}},
  {
    path: 'admin/logistics-callbacks',
    component: LogisticsCallbackAdminComponent,
    canActivate: [AuthGuard],
    data: {roles: ['ADMIN']}
  },
  {path: '**', component: ErrorpageComponent}
];

@NgModule({
  imports: [RouterModule.forRoot(routes)],
  exports: [RouterModule]
})
export class AppRoutingModule {
}
