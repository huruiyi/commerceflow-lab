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
    UseritemComponent
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
