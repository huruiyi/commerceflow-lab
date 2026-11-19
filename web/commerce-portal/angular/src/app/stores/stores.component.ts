import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { finalize, Observable } from 'rxjs';
import { Product } from 'src/model/IProduct';

interface Store {
  merchantId: number;
  name: string;
  description: string;
  status: string;
}

@Component({
  selector: 'app-stores',
  imports: [CommonModule, FormsModule, RouterLink],
  templateUrl: './stores.component.html',
})
export class StoresComponent {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  readonly mode = this.route.snapshot.data['mode'];
  readonly store = signal<Store | null>(null);
  readonly stores = signal<Store[]>([]);
  readonly products = signal<Product[]>([]);
  readonly busy = signal(false);
  readonly message = signal('');
  name = '';
  description = '';
  account = { email: '', password: '', name: '' };

  constructor() {
    if (this.mode === 'admin') this.reload();
    else if (this.mode === 'merchant') {
      this.run(this.http.get<Store>('/api/merchant/store'), (value) => this.select(value));
    } else {
      const id = Number(this.route.snapshot.paramMap.get('id'));
      this.run(this.http.get<Store>(`/api/stores/${id}`), (value) => this.store.set(value));
      this.http.get<Product[]>('/api/products').subscribe({
        next: (values) => this.products.set(values.filter((value) => value.merchantId === id)),
        error: () => this.message.set('商品查询失败 / Could not load products'),
      });
    }
  }

  save(): void {
    this.run(this.http.put<Store>('/api/merchant/store', { name: this.name, description: this.description }), (value) =>
      this.select(value),
    );
  }

  createAccount(): void {
    this.run(this.http.post('/api/users/merchants', this.account), () => {
      this.message.set('商家账号已创建 / Merchant account created');
      this.account = { email: '', password: '', name: '' };
    });
  }

  decide(value: Store, status: string): void {
    this.run(this.http.put<Store>(`/api/admin/stores/${value.merchantId}/status`, { status }), (updated) =>
      this.stores.update((items) => items.map((item) => (item.merchantId === updated.merchantId ? updated : item))),
    );
  }

  private reload(): void {
    this.run(this.http.get<Store[]>('/api/admin/stores'), (values) => this.stores.set(values));
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
    request.pipe(finalize(() => this.busy.set(false))).subscribe({
      next,
      error: (error) => this.message.set(error.error?.message || '操作失败 / Request failed'),
    });
  }
}
