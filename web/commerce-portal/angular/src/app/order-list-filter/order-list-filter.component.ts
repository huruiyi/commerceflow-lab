import { Component, inject, input, output } from '@angular/core';
import { initialOrderSearch, OrderSearchCriteria, OrderSearchPage } from 'src/model/IPurchaseOrder';
import { I18nService } from '../services/i18n.service';

@Component({
  selector: 'app-order-list-filter',
  standalone: false,
  templateUrl: './order-list-filter.component.html',
  styleUrl: './order-list-filter.component.css',
})
export class OrderListFilterComponent {
  readonly i18n = inject(I18nService);
  readonly merchant = input(false);
  readonly result = input<OrderSearchPage>();
  readonly loading = input(false);
  readonly applied = input(initialOrderSearch);
  readonly changed = output<OrderSearchCriteria>();
  readonly statuses = [
    'PENDING_PAYMENT',
    'PAID',
    'CONFIRMED',
    'SHIPPED',
    'RECEIVED',
    'COMPLETED',
    'CANCELLING',
    'CANCELLED',
    'REFUNDING',
    'REFUNDED',
  ];
  draft = { ...initialOrderSearch };

  get invalidDateRange(): boolean {
    return !!(this.draft.from && this.draft.to && this.draft.from > this.draft.to);
  }

  get pageCount(): number {
    const result = this.result();
    return result ? Math.ceil(result.total / result.size) : 0;
  }

  apply(): void {
    if (!this.invalidDateRange) this.changed.emit({ ...this.draft, q: this.draft.q.trim(), page: 0 });
  }

  selectQueue(status: string): void {
    this.draft = { ...initialOrderSearch, status: this.applied().status === status ? '' : status };
    this.apply();
  }

  go(page: number): void {
    this.changed.emit({ ...this.applied(), page });
  }

  clear(): void {
    this.draft = { ...initialOrderSearch };
    this.apply();
  }
}
