import { Component, input, output } from '@angular/core';
import { AfterSaleListCriteria } from '../services/api.service';

@Component({
  selector: 'app-after-sale-search',
  standalone: false,
  templateUrl: './after-sale-search.component.html',
  styleUrl: './after-sale-search.component.css',
})
export class AfterSaleSearchComponent {
  readonly busy = input(false);
  readonly statuses = input.required<string[]>();
  readonly statusKey = input.required<string>();
  readonly changed = output<AfterSaleListCriteria>();
  orderId?: number;
  caseId = '';
  status = '';
  from = '';
  through = '';

  submit(): void {
    const criteria: AfterSaleListCriteria = {};
    if (this.orderId) criteria.orderId = this.orderId;
    if (this.caseId.trim()) criteria.caseId = this.caseId.trim();
    if (this.status) criteria.status = this.status;
    if (this.from) criteria.createdFrom = new Date(this.from + 'T00:00:00').toISOString();
    if (this.through) {
      const midnight = new Date(this.through + 'T00:00:00');
      midnight.setDate(midnight.getDate() + 1);
      criteria.createdBefore = midnight.toISOString();
    }
    this.changed.emit(criteria);
  }

  clear(): void {
    this.orderId = undefined;
    this.caseId = this.status = this.from = this.through = '';
    this.changed.emit({});
  }
}
