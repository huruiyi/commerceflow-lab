import { Component, computed, inject, input, output } from '@angular/core';
import { HlmButton } from '@spartan-ng/helm/button';
import { I18nService } from '../services/i18n.service';

@Component({
  selector: 'app-list-pagination',
  imports: [HlmButton],
  templateUrl: './list-pagination.component.html',
  styleUrl: './list-pagination.component.css',
})
export class ListPaginationComponent {
  readonly i18n = inject(I18nService);
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly label = input.required<string>();
  readonly loading = input(false);
  readonly pageChange = output<number>();

  readonly tokens = computed(() => {
    const last = this.totalPages() - 1;
    if (last < 5) return Array.from({ length: Math.max(0, last + 1) }, (_, page) => page);

    const current = this.page();
    const start = Math.max(0, Math.min(current - 1, last - 2));
    const pages = [...new Set([0, start, start + 1, start + 2, last])];
    return pages.flatMap((page, index) => (index > 0 && page - pages[index - 1] > 1 ? [-(index + 1), page] : [page]));
  });

  select(page: number): void {
    if (!this.loading() && page >= 0 && page < this.totalPages() && page !== this.page()) {
      this.pageChange.emit(page);
    }
  }
}
