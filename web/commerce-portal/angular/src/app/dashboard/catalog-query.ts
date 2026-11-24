import { ParamMap, Params } from '@angular/router';
import { readPage } from '../list-pagination/page-query';

export const catalogSorts = ['default', 'price-asc', 'price-desc', 'rating'] as const;
export type CatalogSort = (typeof catalogSorts)[number];

export const catalogSortOptions: { value: CatalogSort; label: string }[] = [
  { value: 'default', label: 'catalogDefaultSort' },
  { value: 'price-asc', label: 'catalogPriceAscending' },
  { value: 'price-desc', label: 'catalogPriceDescending' },
  { value: 'rating', label: 'catalogRatingDescending' },
];

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

export function catalogSearchParams(query: CatalogQuery, size: number): Record<string, string | number> {
  const { q, sort, page, merchantId, minPrice, maxPrice } = query;
  const params: Record<string, string | number> = { q, sort, page, size };
  if (merchantId !== null) params['merchantId'] = merchantId;
  if (minPrice !== null) params['minPrice'] = minPrice;
  if (maxPrice !== null) params['maxPrice'] = maxPrice;
  return params;
}

export function invalidCatalogPrice(query: CatalogQuery): boolean {
  const { minPrice, maxPrice } = query;
  return (
    [minPrice, maxPrice].some((price) => price !== null && (price < 0 || Number(price.toFixed(2)) !== price)) ||
    (minPrice !== null && maxPrice !== null && minPrice > maxPrice)
  );
}

export function invalidCatalogKeyword(value: string): boolean {
  const keyword = value.trim();
  return keyword.length > 120 || new Set(keyword.split(/\s+/u)).size > 12;
}

export function readPositiveId(value: string | null): number | null {
  const number = Number(value);
  return value !== null && /^\d+$/.test(value) && Number.isSafeInteger(number) && number > 0 ? number : null;
}

function readPrice(value: string | null): number | null {
  const number = Number(value);
  return value?.trim() && Number.isFinite(number) && number >= 0 ? number : null;
}
