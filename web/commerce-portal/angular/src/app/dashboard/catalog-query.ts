import { ParamMap, Params } from '@angular/router';
import { readPage } from '../list-pagination/page-query';

export const catalogSorts = ['default', 'price-asc', 'price-desc', 'rating'] as const;
export type CatalogSort = (typeof catalogSorts)[number];

export interface CatalogQuery {
  q: string;
  merchantId: number | null;
  minPrice: number | null;
  maxPrice: number | null;
  sort: CatalogSort;
  page: number;
}

export function readCatalogQuery(params: ParamMap): CatalogQuery {
  const sort = catalogSorts.find((value) => value === params.get('sort')) ?? 'default';
  return {
    q: params.get('q')?.trim() ?? '',
    merchantId: readPositiveId(params.get('merchantId')),
    minPrice: readPrice(params.get('minPrice')),
    maxPrice: readPrice(params.get('maxPrice')),
    sort,
    page: readPage(params.get('page')),
  };
}

export function catalogQueryParams(query: CatalogQuery): Params {
  return {
    q: query.q || null,
    merchantId: query.merchantId,
    minPrice: query.minPrice,
    maxPrice: query.maxPrice,
    sort: query.sort === 'default' ? null : query.sort,
    page: query.page || null,
  };
}

export function readPositiveId(value: string | null): number | null {
  const number = Number(value);
  return value !== null && /^\d+$/.test(value) && Number.isSafeInteger(number) && number > 0 ? number : null;
}

function readPrice(value: string | null): number | null {
  const number = Number(value);
  return value?.trim() && Number.isFinite(number) && number >= 0 ? number : null;
}
