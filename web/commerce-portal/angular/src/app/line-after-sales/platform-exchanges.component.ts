import { ChangeDetectorRef, Component, DestroyRef, OnInit, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { AfterSale, ApiService, ExchangeDetail } from '../services/api.service';

@Component({
  standalone: false,
  selector: 'app-platform-exchanges',
  templateUrl: './platform-exchanges.component.html',
})
export class PlatformExchangesComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroy = inject(DestroyRef);
  private readonly changes = inject(ChangeDetectorRef);
  exchanges: AfterSale[] = [];
  detail?: ExchangeDetail;
  orderId?: number;
  busy = false;
  failed = false;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.run(this.api.platformExchanges(this.orderId), (values) => {
      this.exchanges = values;
      this.detail = undefined;
    });
  }

  show(id: string): void {
    this.detail = undefined;
    this.run(this.api.platformExchange(id), (detail) => (this.detail = detail));
  }

  private run<T>(request: Observable<T>, success: (value: T) => void): void {
    this.busy = true;
    this.failed = false;
    request
      .pipe(
        takeUntilDestroyed(this.destroy),
        finalize(() => {
          this.busy = false;
          this.changes.markForCheck();
        }),
      )
      .subscribe({
        next: success,
        error: () => (this.failed = true),
      });
  }
}
