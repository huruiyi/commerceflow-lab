import { ErrorHandler, NgModule } from '@angular/core';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { BrowserModule } from '@angular/platform-browser';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { LoginComponent } from './login/login.component';
import { DashboardComponent } from './dashboard/dashboard.component';
import { NavbarComponent } from './inc/navbar/navbar.component';
import { ErrorpageComponent } from './errorpage/errorpage.component';
import { OrderComponent } from './order/order.component';
import { UsersComponent } from './users/users.component';
import { TrdatePipe } from './pipes/trdate.pipe';
import { TrcurrencyPipe } from './pipes/trcurrency.pipe';
import { ApptitleDirective } from './directive/apptitle.directive';
import { GlobalErrorHandler } from './services/GlobalErrorHandler';
import { UseritemComponent } from './inc/useritem/useritem.component';
import { AddressesComponent } from './addresses/addresses.component';
import { PurchaseOrdersComponent } from './purchase-orders/purchase-orders.component';
import { MerchantOrdersComponent } from './merchant-orders/merchant-orders.component';
import { ProfileComponent } from './profile/profile.component';
import { TranslatePipe } from './pipes/translate.pipe';
import { SchedulingAdminComponent } from './scheduling-admin/scheduling-admin.component';
import { LogisticsCallbackAdminComponent } from './logistics-callback-admin/logistics-callback-admin.component';

@NgModule({
  declarations: [
    AppComponent,
    LoginComponent,
    DashboardComponent,
    NavbarComponent,
    ErrorpageComponent,
    OrderComponent,
    UsersComponent,
    TrdatePipe,
    TrcurrencyPipe,
    ApptitleDirective,
    UseritemComponent,
    AddressesComponent,
    PurchaseOrdersComponent,
    MerchantOrdersComponent,
    ProfileComponent,
    TranslatePipe,
    SchedulingAdminComponent,
    LogisticsCallbackAdminComponent
  ],
  imports: [
    BrowserModule,
    AppRoutingModule,
    FormsModule,
    ReactiveFormsModule,
    HttpClientModule
  ],
  providers: [
    {provide: ErrorHandler, useClass: GlobalErrorHandler}],
  bootstrap: [AppComponent]
})
export class AppModule { }
