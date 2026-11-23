import {
  afterNextRender,
  ChangeDetectorRef,
  Component,
  DestroyRef,
  ElementRef,
  Injector,
  OnInit,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { finalize, Observable } from 'rxjs';
import { I18nService } from '../services/i18n.service';
import { AfterSale, AfterSaleListCriteria, ApiService, ExchangeDetail } from '../services/api.service';

@Component({
  standalone: false,
  selector: 'app-platform-exchanges',
  templateUrl: './platform-exchanges.component.html',
  styleUrl: './platform-exchanges.component.css',
})
export class PlatformExchangesComponent implements OnInit {
  private readonly api = inject(ApiService);
  private readonly destroy = inject(DestroyRef);
  private readonly changes = inject(ChangeDetectorRef);
  private readonly host: ElementRef<HTMLElement> = inject(ElementRef);
  private readonly injector = inject(Injector);
  readonly i18n = inject(I18nService);
  exchanges: AfterSale[] = [];
  detail?: ExchangeDetail;
  readonly statuses = [
    'APPLIED',
    'WAITING_RETURN',
    'RETURN_SENT',
    'WAITING_MERCHANT',
    'PREPARING',
    'REVIEW_REQUIRED',
    'EXCHANGE_OUT_OF_STOCK',
    'EXCHANGE_READY',
    'EXCHANGE_SHIPPING',
    'RELEASING',
    'COMPLETED',
    'REJECTED',
    'FAILED',
  ];
  page = 0;
  totalPages = 0;
  total = 0;
  private criteria: AfterSaleListCriteria = {};
  busy = false;
  failed = false;

  ngOnInit(): void {
    this.load();
  }

  applyCriteria(criteria: AfterSaleListCriteria): void {
    this.criteria = criteria;
    this.load(0);
  }

  load(page = this.page): void {
    this.run(this.api.platformExchanges(this.criteria, page), (result) => {
      this.exchanges = result.rows;
      this.page = result.page;
      this.total = result.total;
      this.totalPages = Math.ceil(result.total / result.size);
      this.detail = undefined;
    });
  }

  show(id: string): void {
    this.detail = undefined;
    this.run(this.api.platformExchange(id), (detail) => {
      this.detail = detail;
      afterNextRender(() => this.host.nativeElement.querySelector<HTMLElement>('#exchange-detail-title')?.focus(), {
        injector: this.injector,
      });
    });
  }

  private run<T>(request: Observable<T>, success: (value: T) => void): void {
    if (this.busy) return;
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
