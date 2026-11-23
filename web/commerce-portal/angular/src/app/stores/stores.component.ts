import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, NavigationEnd, Router, RouterLink } from '@angular/router';
import {
  catchError,
  distinctUntilChanged,
  EMPTY,
  filter,
  finalize,
  map,
  merge,
  Observable,
  startWith,
  switchMap,
} from 'rxjs';
import { CatalogProductCardComponent } from '../dashboard/catalog-product-card.component';
import { ListPaginationComponent } from '../list-pagination/list-pagination.component';
import { readPage } from '../list-pagination/page-query';
import { ApiService, CatalogSearchPage } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

interface Store {
  merchantId: number;
  name: string;
  description: string;
  status: string;
}

@Component({
  selector: 'app-stores',
  imports: [CommonModule, FormsModule, RouterLink, ListPaginationComponent, CatalogProductCardComponent],
  templateUrl: './stores.component.html',
  styleUrl: './stores.component.css',
})
export class StoresComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly api = inject(ApiService);
  private readonly destroy = inject(DestroyRef);
  readonly i18n = inject(I18nService);
  readonly mode = this.route.snapshot.data['mode'];
  readonly store = signal<Store | null>(null);
  readonly stores = signal<Store[]>([]);
  readonly storeStatuses = ['PENDING', 'ACTIVE', 'REJECTED', 'DISABLED'];
  readonly storeQuery = signal('');
  readonly storeStatus = signal('');
  readonly storeListLoading = signal(false);
  readonly storeListFailed = signal(false);
  readonly matchingStores = computed(() => {
    const query = this.storeQuery().trim().toLocaleLowerCase();
    const merchantId = query.replace(/^#/, '');
    const status = this.storeStatus();
    return this.stores().filter(
      (store) =>
        (!status || store.status === status) &&
        (!query || store.name.toLocaleLowerCase().includes(query) || String(store.merchantId) === merchantId),
    );
  });
  private readonly storePageSize = 10;
  private readonly requestedStorePage = signal(0);
  readonly storePageCount = computed(() => Math.ceil(this.matchingStores().length / this.storePageSize));
  readonly storePage = computed(() => Math.min(this.requestedStorePage(), Math.max(0, this.storePageCount() - 1)));
  readonly pageStores = computed(() => {
    const start = this.storePage() * this.storePageSize;
    return this.matchingStores().slice(start, start + this.storePageSize);
  });
  readonly productPage = signal<CatalogSearchPage | null>(null);
  readonly totalPages = computed(() => {
    const page = this.productPage();
    return page ? Math.ceil(page.total / page.size) : 0;
  });
  readonly productsLoading = signal(false);
  readonly productsFailed = signal(false);
  private readonly pageSize = 12;
  readonly busy = signal(false);
  readonly message = signal('');
  readonly actionFailed = signal(false);
  createOpen = false;
  name = '';
  description = '';
  account = { email: '', password: '', name: '' };

  constructor() {
    if (this.mode === 'admin') this.reload();
    else if (this.mode === 'merchant') {
      this.loadStore();
    } else {
      this.route.paramMap
        .pipe(
          map((params) => Number(params.get('id'))),
          distinctUntilChanged(),
          switchMap((id) => {
            this.store.set(null);
            this.productPage.set(null);
            this.message.set('');
            return this.http.get<Store>(`/api/stores/${id}`).pipe(
              catchError((error) => {
                this.message.set(error.error?.message || '操作失败 / Request failed');
                return EMPTY;
              }),
            );
          }),
          takeUntilDestroyed(),
        )
        .subscribe((value) => this.store.set(value));
      merge(this.router.events.pipe(filter((event) => event instanceof NavigationEnd)), this.i18n.localeChanged)
        .pipe(
          startWith(null),
          map(() => ({
            merchantId: Number(this.route.snapshot.paramMap.get('id')),
            page: readPage(this.route.snapshot.queryParamMap.get('page')),
            locale: this.i18n.locale(),
          })),
          distinctUntilChanged((a, b) => a.merchantId === b.merchantId && a.page === b.page && a.locale === b.locale),
          switchMap(({ merchantId, page }) => {
            this.productsLoading.set(true);
            this.productsFailed.set(false);
            return this.api.searchProducts({ merchantId, page, size: this.pageSize }).pipe(
              catchError(() => {
                this.productPage.set(null);
                this.productsFailed.set(true);
                return EMPTY;
              }),
              finalize(() => this.productsLoading.set(false)),
            );
          }),
          takeUntilDestroyed(),
        )
        .subscribe((value) => this.productPage.set(value));
    }
  }

  filterStores(query: string, status: string): void {
    this.storeQuery.set(query);
    this.storeStatus.set(status);
    this.requestedStorePage.set(0);
  }

  changeStorePage(page: number): void {
    this.requestedStorePage.set(page);
  }

  changePage(page: number): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { page: page || null },
      queryParamsHandling: 'merge',
    });
  }

  loadStore(): void {
    this.run(this.http.get<Store>('/api/merchant/store'), (value) => this.select(value));
  }

  save(): void {
    this.run(
      this.http.put<Store>('/api/merchant/store', { name: this.name, description: this.description }),
      (value) => {
        this.select(value);
        this.message.set('storeMerchantSaved');
      },
    );
  }

  createAccount(): void {
    this.run(this.http.post('/api/users/merchants', this.account), () => {
      this.message.set('storeAdminAccountCreated');
      this.account = { email: '', password: '', name: '' };
    });
  }

  toggleAccountForm(): void {
    this.createOpen = !this.createOpen;
    if (!this.createOpen) this.account.password = '';
  }

  decide(value: Store, status: string): void {
    this.run(this.http.put<Store>(`/api/admin/stores/${value.merchantId}/status`, { status }), (updated) =>
      this.stores.update((items) => items.map((item) => (item.merchantId === updated.merchantId ? updated : item))),
    );
  }

  reload(): void {
    if (this.storeListLoading()) return;
    this.storeListLoading.set(true);
    this.storeListFailed.set(false);
    this.http
      .get<Store[]>('/api/admin/stores')
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.storeListLoading.set(false)),
      )
      .subscribe({
        next: (values) => this.stores.set(values),
        error: () => this.storeListFailed.set(true),
      });
  }

  private select(store: Store | null): void {
    this.store.set(store);
    this.name = store?.name ?? '';
    this.description = store?.description ?? '';
  }

  private run<T>(request: Observable<T>, next: (value: T) => void): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.message.set('');
    this.actionFailed.set(false);
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next,
        error: (error) => {
          this.actionFailed.set(true);
          this.message.set(error.error?.message || 'requestFailed');
        },
      });
  }
}
