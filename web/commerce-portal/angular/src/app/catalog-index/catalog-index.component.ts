import { DatePipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';

interface IndexStatus {
  elasticsearch: { phase: string; generation: number; pending: number; oldestAt: string | null } | null;
}

interface Difference {
  productId: number;
  reason: string;
  expectedVersion: number | null;
  indexedVersion: number | null;
}

interface Comparison {
  status: string;
  generation: number;
  checked: number;
  nextAfterId: number | null;
  differences: Difference[];
}

@Component({
  imports: [DatePipe],
  templateUrl: './catalog-index.component.html',
})
export class CatalogIndexComponent {
  private readonly root = '/api/admin/catalog-search';
  private readonly http = inject(HttpClient);
  private readonly destroy = inject(DestroyRef);
  readonly status = signal<IndexStatus | null>(null);
  readonly comparison = signal<Comparison | null>(null);
  readonly busy = signal(false);
  readonly failed = signal(false);
  readonly labels: Record<string, string> = {
    DISABLED: '未启用 / Disabled',
    INITIALIZING: '初始化中 / Initializing',
    BUILDING: '重建中 / Building',
    CATCHING_UP: '有待同步事件 / Catching up',
    READY: '可用 / Ready',
    UNAVAILABLE: '暂不可用 / Unavailable',
    CHECKED: '本批已核对 / Batch checked',
    RESTART: '索引已切换，请从头核对 / Index changed. Start again.',
    MISSING: '缺少文档 / Missing',
    EXTRA: '多余文档 / Unexpected',
    DIFFERENT: '内容不一致 / Differs',
  };

  constructor() {
    this.refresh();
  }

  refresh(): void {
    this.run(this.http.get<IndexStatus>(this.root), (result) => this.status.set(result));
  }

  rebuild(): void {
    this.run(this.http.post<IndexStatus>(this.root + '/rebuild', {}), (result) => {
      this.status.set(result);
      this.comparison.set(null);
    });
  }

  compare(next = false): void {
    let params = new HttpParams();
    const page = this.comparison();
    if (next && page?.nextAfterId != null) {
      params = params.set('afterId', page.nextAfterId).set('generation', page.generation);
    }
    this.run(this.http.get<Comparison>(this.root + '/comparison', { params }), (result) => this.comparison.set(result));
  }

  private run<T>(action: Observable<T>, done: (result: T) => void): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.failed.set(false);
    action
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => this.busy.set(false)),
      )
      .subscribe({
        next: done,
        error: () => this.failed.set(true),
      });
  }
}
