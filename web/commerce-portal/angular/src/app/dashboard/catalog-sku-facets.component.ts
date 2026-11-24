import { Component, computed, inject, input, output } from '@angular/core';
import { CatalogSkuFacet } from '../services/api.service';
import { I18nService } from '../services/i18n.service';
import { invalidCatalogSku } from './catalog-query';

interface FacetOption {
  value: string;
  productCount: number | null;
}

@Component({
  selector: 'app-catalog-sku-facets',
  templateUrl: './catalog-sku-facets.component.html',
  styleUrl: './catalog-sku-facets.component.css',
})
export class CatalogSkuFacetsComponent {
  readonly facets = input<readonly CatalogSkuFacet[]>([]);
  readonly sku = input<string>();
  readonly loading = input(false);
  readonly skuChange = output<string>();
  readonly i18n = inject(I18nService);

  readonly selected = computed(() => {
    const sku = this.sku();
    if (invalidCatalogSku(sku)) return new Map<string, string>();

    return new Map(
      (sku ?? '')
        .split(';')
        .filter((entry) => entry.trim())
        .map((entry) => {
          const separator = entry.indexOf('=');
          return [
            entry.slice(0, separator).trim().toLowerCase(),
            entry
              .slice(separator + 1)
              .trim()
              .toLowerCase(),
          ];
        }),
    );
  });

  readonly groups = computed(() => {
    const groups = new Map<string, FacetOption[]>();
    for (const { name, value, productCount } of this.facets()) {
      const options = groups.get(name) ?? [];
      options.push({ value, productCount });
      groups.set(name, options);
    }
    for (const [name, value] of this.selected()) {
      const options = groups.get(name) ?? [];
      if (!options.some((option) => option.value === value)) options.unshift({ value, productCount: null });
      groups.set(name, options);
    }
    return groups;
  });

  cannotApply(name: string, value: string): boolean {
    if (this.loading()) return true;
    const selected = this.selected();
    return selected.get(name) !== value && invalidCatalogSku(this.updatedSku(name, value));
  }

  change(name: string, value: string): void {
    if (!this.cannotApply(name, value)) this.skuChange.emit(this.updatedSku(name, value));
  }

  private updatedSku(name: string, value: string): string {
    const selected = new Map(this.selected());
    if (selected.get(name) === value) {
      selected.delete(name);
    } else {
      selected.set(name, value);
    }
    return Array.from(selected, ([key, choice]) => key + '=' + choice).join(';');
  }
}
