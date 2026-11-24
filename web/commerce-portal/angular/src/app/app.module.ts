import { ErrorHandler, NgModule } from '@angular/core';
import { HttpClientModule } from '@angular/common/http';
import { FormsModule, ReactiveFormsModule } from '@angular/forms';
import { BrowserModule } from '@angular/platform-browser';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

import { AppRoutingModule } from './app-routing.module';
import { AppComponent } from './app.component';
import { InvoiceCenterComponent } from './invoices/invoice-center.component';
import { LoginComponent } from './login/login.component';
import { DashboardComponent } from './dashboard/dashboard.component';
import { CatalogImageComponent } from './dashboard/catalog-image.component';
import { CatalogProductCardComponent } from './dashboard/catalog-product-card.component';
import { CatalogSkuFacetsComponent } from './dashboard/catalog-sku-facets.component';
import { CatalogSearchFormComponent } from './dashboard/catalog-search-form.component';
import { ProductDetailComponent } from './product-detail/product-detail.component';
import { NavbarComponent } from './inc/navbar/navbar.component';
import { ErrorpageComponent } from './errorpage/errorpage.component';
import { CouponsComponent } from './coupons/coupons.component';
import { OrderComponent } from './order/order.component';
import { UsersComponent } from './users/users.component';
import { TrdatePipe } from './pipes/trdate.pipe';
import { TrcurrencyPipe } from './pipes/trcurrency.pipe';
import { ApptitleDirective } from './directive/apptitle.directive';
import { GlobalErrorHandler } from './services/GlobalErrorHandler';
import { UseritemComponent } from './inc/useritem/useritem.component';
import { AddressesComponent } from './addresses/addresses.component';
import { PurchaseOrdersComponent } from './purchase-orders/purchase-orders.component';
import { LineAfterSalesComponent } from './line-after-sales/line-after-sales.component';
import { AfterSaleTrackingComponent } from './line-after-sales/after-sale-tracking.component';
import { PlatformExchangesComponent } from './line-after-sales/platform-exchanges.component';
import { MerchantOrdersComponent } from './merchant-orders/merchant-orders.component';
import { MerchantReturnWorkOrdersComponent } from './merchant-return-work-orders/merchant-return-work-orders.component';
import { OrderListFilterComponent } from './order-list-filter/order-list-filter.component';
import { AfterSaleSearchComponent } from './line-after-sales/after-sale-search.component';
import { ListPaginationComponent } from './list-pagination/list-pagination.component';
import { MerchantCatalogComponent } from './merchant-catalog/merchant-catalog.component';
import { ProfileComponent } from './profile/profile.component';
import { TranslatePipe } from './pipes/translate.pipe';
import { SchedulingAdminComponent } from './scheduling-admin/scheduling-admin.component';
import { LogisticsCallbackAdminComponent } from './logistics-callback-admin/logistics-callback-admin.component';
import { MerchantInventoryComponent } from './merchant-inventory/merchant-inventory.component';
import { PaymentLedgerReportsComponent } from './payment-ledger-reports/payment-ledger-reports.component';
import { InventoryReconciliationAdminComponent } from './inventory-reconciliation/inventory-reconciliation.component';

@NgModule({
  declarations: [
    AppComponent,
    LoginComponent,
    DashboardComponent,
    ProductDetailComponent,
    NavbarComponent,
    ErrorpageComponent,
    OrderComponent,
    CouponsComponent,
    UsersComponent,
    TrdatePipe,
    TrcurrencyPipe,
    ApptitleDirective,
    UseritemComponent,
    AddressesComponent,
    PurchaseOrdersComponent,
    InvoiceCenterComponent,
    MerchantOrdersComponent,
    MerchantReturnWorkOrdersComponent,
    LineAfterSalesComponent,
    AfterSaleTrackingComponent,
    PlatformExchangesComponent,
    OrderListFilterComponent,
    AfterSaleSearchComponent,
    MerchantCatalogComponent,
    ProfileComponent,
    TranslatePipe,
    SchedulingAdminComponent,
    LogisticsCallbackAdminComponent,
    MerchantInventoryComponent,
    InventoryReconciliationAdminComponent,
    PaymentLedgerReportsComponent,
  ],
  imports: [
    BrowserModule,
    BrowserAnimationsModule,
    AppRoutingModule,
    FormsModule,
    ReactiveFormsModule,
    HttpClientModule,
    HlmButton,
    CatalogImageComponent,
    CatalogProductCardComponent,
    CatalogSkuFacetsComponent,
    CatalogSearchFormComponent,
    ListPaginationComponent,
    HlmBadge,
    HlmSpinner,
    ...HlmCardImports,
    ...HlmAlertImports,
  ],
  providers: [{ provide: ErrorHandler, useClass: GlobalErrorHandler }],
  bootstrap: [AppComponent],
})
export class AppModule {}
