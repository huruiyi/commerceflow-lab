import { Component, inject, input, OnChanges, output, SimpleChanges } from '@angular/core';
import {
  initialOrderSearch,
  OrderSearchCriteria,
  OrderSearchPage,
  orderSearchStatuses,
} from 'src/model/IPurchaseOrder';
import { I18nService } from '../services/i18n.service';

@Component({
  selector: 'app-order-list-filter',
  standalone: false,
  templateUrl: './order-list-filter.component.html',
  styleUrl: './order-list-filter.component.css',
})
export class OrderListFilterComponent implements OnChanges {
  readonly i18n = inject(I18nService);
  readonly merchant = input(false);
  readonly result = input<OrderSearchPage>();
  readonly loading = input(false);
  readonly applied = input(initialOrderSearch);
  readonly changed = output<OrderSearchCriteria>();
  readonly statuses = orderSearchStatuses;
  draft = { ...initialOrderSearch };
  datesOpen = false;

  ngOnChanges(changes: SimpleChanges<OrderListFilterComponent>): void {
    const change = changes.applied;
    if (!change) return;
    const previous = change.previousValue as OrderSearchCriteria | undefined;
    const current = this.applied();
    if (
      !previous ||
      previous.q !== current.q ||
      previous.status !== current.status ||
      previous.from !== current.from ||
      previous.to !== current.to
    ) {
      this.draft = { ...current };
      if (current.from || current.to) this.datesOpen = true;
    }
  }

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
    this.datesOpen = false;
    this.apply();
  }
}
