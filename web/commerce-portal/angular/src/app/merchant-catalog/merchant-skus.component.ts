import { CurrencyPipe, KeyValuePipe } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, Observable } from 'rxjs';
import { CatalogSku } from 'src/model/ICatalogSku';

@Component({
  imports: [CurrencyPipe, KeyValuePipe, FormsModule, RouterLink],
  templateUrl: './merchant-skus.component.html',
})
export class MerchantSkusComponent {
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  readonly productId = Number(inject(ActivatedRoute).snapshot.paramMap.get('id'));
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
        this.error.set('请填写完整且不重复的规格 / Enter distinct specification names and values.');
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
        error: (error) => this.error.set(error.error?.message || '操作未完成 / Request failed'),
      });
  }
}
