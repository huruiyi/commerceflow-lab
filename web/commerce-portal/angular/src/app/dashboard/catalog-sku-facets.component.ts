import { Component, computed, inject, input, output } from '@angular/core';
import { CatalogSkuFacet } from '../services/api.service';
import { I18nService } from '../services/i18n.service';
import { invalidCatalogAttributes, invalidCatalogSku } from './catalog-query';

const facetLabels = new Map([
  ['brand', 'catalogFacetBrand'],
  ['screen size', 'catalogFacetScreenSize'],
  ['resolution', 'catalogFacetResolution'],
  ['panel', 'catalogFacetPanel'],
  ['refresh rate', 'catalogFacetRefresh'],
  ['port', 'catalogFacetPort'],
  ['package', 'catalogFacetPackage'],
]);

interface FacetOption {
  value: string;
  productCount: number | null;
}

@Component({
  selector: 'app-catalog-facets',
  templateUrl: './catalog-sku-facets.component.html',
  styleUrl: './catalog-sku-facets.component.css',
})
export class CatalogSkuFacetsComponent {
  readonly facets = input<readonly CatalogSkuFacet[]>([]);
  readonly selection = input<string>();
  readonly kind = input<'attributes' | 'sku'>('sku');
  readonly loading = input(false);
  readonly selectionChange = output<string>();
  readonly i18n = inject(I18nService);

  readonly selected = computed(() => {
    const selection = this.selection();
    if (this.invalid(selection)) return new Map<string, string>();

    return new Map(
      (selection ?? '')
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
    const ordered = new Map<string, FacetOption[]>();
    for (const name of facetLabels.keys()) {
      const options = groups.get(name);
      if (!options) continue;
      if (name === 'screen size' || name === 'refresh rate') {
        options.sort((left, right) => Number(left.value) - Number(right.value));
      }
      ordered.set(name, options);
    }
    return new Map([...ordered, ...groups]);
  });

  label(name: string): string {
    const key = facetLabels.get(name);
    return key ? this.i18n.t(key) : name;
  }

  get groupLabel(): string {
    return this.i18n.t(this.kind() === 'attributes' ? 'catalogAttributes' : 'catalogSkuFilter');
  }

  get clearLabel(): string {
    return this.i18n.t(this.kind() === 'attributes' ? 'catalogClearAttributes' : 'catalogClearSku');
  }

  valueLabel(name: string, value: string): string {
    if (name === 'package') return this.i18n.catalogPackage(value);
    if (name === 'screen size') return value + '″';
    if (name === 'refresh rate') return value + ' Hz';
    if (name === 'panel' || name === 'port') return value.toUpperCase();
    return value;
  }

  private invalid(value?: string): boolean {
    return this.kind() === 'attributes' ? invalidCatalogAttributes(value) : invalidCatalogSku(value);
  }

  cannotApply(name: string, value: string): boolean {
    if (this.loading()) return true;
    const selected = this.selected();
    return selected.get(name) !== value && this.invalid(this.updatedSelection(name, value));
  }

  change(name: string, value: string): void {
    if (!this.cannotApply(name, value)) this.selectionChange.emit(this.updatedSelection(name, value));
  }

  private updatedSelection(name: string, value: string): string {
    const selected = new Map(this.selected());
    if (selected.get(name) === value) {
      selected.delete(name);
    } else {
      selected.set(name, value);
    }
    return Array.from(selected, ([key, choice]) => key + '=' + choice).join(';');
  }
}
