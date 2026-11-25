import { CurrencyPipe, KeyValuePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { afterNextRender, Component, DestroyRef, ElementRef, inject, Injector, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { HlmAlertImports } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
import { finalize, Observable } from 'rxjs';
import { CatalogSku } from 'src/model/ICatalogSku';
import { CatalogImageComponent } from '../dashboard/catalog-image.component';
import { readPage } from '../list-pagination/page-query';
import { ApiService, MerchantProduct } from '../services/api.service';
import { I18nService } from '../services/i18n.service';

@Component({
  imports: [CurrencyPipe, KeyValuePipe, FormsModule, RouterLink, HlmButton, HlmAlertImports, CatalogImageComponent],
  templateUrl: './merchant-skus.component.html',
  styleUrl: './merchant-skus.component.css',
})
export class MerchantSkusComponent {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly injector = inject(Injector);
  private readonly api = inject(ApiService);
  readonly i18n = inject(I18nService);
  readonly product = signal<MerchantProduct | undefined>(undefined);
  private readonly route = inject(ActivatedRoute);
  readonly productId = Number(this.route.snapshot.paramMap.get('id'));
  readonly listPage = readPage(this.route.snapshot.queryParamMap.get('page'));
  private readonly endpoint = `/api/merchant/catalog/v1/products/${this.productId}/skus`;
  readonly skus = signal<CatalogSku[]>([]);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly saved = signal(false);
  readonly editing = signal<CatalogSku | null>(null);
  readonly formOpen = signal(false);
  code = '';
  unitPrice = 0;
  status: CatalogSku['status'] = 'INACTIVE';
  specifications = [{ name: '', value: '' }];
  private pending?: { fingerprint: string; requestId: string };

  constructor() {
    this.load();
    this.api
      .merchantProduct(this.productId)
      .pipe(takeUntilDestroyed(this.destroy))
      .subscribe({
        next: (product) => this.product.set(product),
        error: () => this.product.set(undefined),
      });
  }

  load(): void {
    this.run(this.http.get<CatalogSku[]>(this.endpoint), (rows) => this.skus.set(rows));
  }

  open(sku: CatalogSku | null = null): void {
    this.editing.set(sku);
    this.code = sku?.code ?? '';
    this.unitPrice = sku?.unitPrice ?? 0;
    this.status = sku?.status ?? 'INACTIVE';
    this.specifications = [{ name: '', value: '' }];
    this.formOpen.set(true);
    this.saved.set(false);
    this.error.set('');
    afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('#sku-form-title')?.focus(), {
      injector: this.injector,
    });
  }

  addSpecification(): void {
    if (this.specifications.length < 4) this.specifications.push({ name: '', value: '' });
  }

  removeSpecification(index: number): void {
    if (this.specifications.length > 1) this.specifications.splice(index, 1);
  }

  save(): void {
    const sku = this.editing();
    const code = this.code.trim();
    if (!code) return;
    const fields = { code, unitPrice: this.unitPrice, status: this.status };
    let action: Observable<CatalogSku>;
    if (sku) {
      action = this.http.put<CatalogSku>(`${this.endpoint}/${sku.skuId}`, {
        ...fields,
        expectedVersion: sku.businessVersion,
      });
    } else {
      const specs = this.specifications.map(({ name, value }) => [name.trim(), value.trim()] as const);
      if (
        specs.some(([name, value]) => !name || !value) ||
        new Set(specs.map(([name]) => name.toLowerCase())).size !== specs.length
      ) {
        this.error.set(this.i18n.t('merchantSkuInvalidSpecs'));
        return;
      }
      const body = { ...fields, specifications: Object.fromEntries(specs) };
      const fingerprint = JSON.stringify(body);
      if (this.pending?.fingerprint !== fingerprint) this.pending = { fingerprint, requestId: crypto.randomUUID() };
      action = this.http.post<CatalogSku>(this.endpoint, { ...body, requestId: this.pending.requestId });
    }
    this.run(action, (result) => {
      this.skus.update((rows) =>
        sku ? rows.map((row) => (row.skuId === result.skuId ? result : row)) : [...rows, result],
      );
      this.saved.set(true);
      this.formOpen.set(false);
      this.pending = undefined;
    });
  }

  private run<T>(request: Observable<T>, next: (result: T) => void): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next,
        error: (error) => this.error.set(error.error?.message || this.i18n.t('requestFailed')),
      });
  }
}
