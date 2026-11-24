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

export const catalogMatchOptions = [
  { value: 'all', label: 'catalogMatchAll' },
  { value: 'any', label: 'catalogMatchAny' },
  { value: 'phrase', label: 'catalogMatchPhrase' },
] as const;

export interface CatalogQuery {
  match: (typeof catalogMatchOptions)[number]['value'];
  exclude: string;
  q: string;
  sku?: string;
  attributes?: string;
  merchantId: number | null;
  minPrice: number | null;
  maxPrice: number | null;
  sort: CatalogSort;
  page: number;
}

export function readCatalogQuery(params: ParamMap): CatalogQuery {
  const sort = catalogSorts.find((value) => value === params.get('sort')) ?? 'default';
  return {
    match: catalogMatchOptions.find((option) => option.value === params.get('match'))?.value ?? 'all',
    exclude: params.get('exclude')?.trim() ?? '',
    q: params.get('q')?.trim() ?? '',
    sku: params.get('sku')?.trim() || undefined,
    attributes: params.get('attributes')?.trim() || undefined,
    merchantId: readPositiveId(params.get('merchantId')),
    minPrice: readPrice(params.get('minPrice')),
    maxPrice: readPrice(params.get('maxPrice')),
    sort,
    page: readPage(params.get('page')),
  };
}

export function catalogQueryParams(query: CatalogQuery): Params {
  return {
    q: query.q.trim() || null,
    ...(query.match !== 'all' ? { match: query.match } : {}),
    ...(query.exclude.trim() ? { exclude: query.exclude.trim() } : {}),
    ...(query.sku ? { sku: query.sku } : {}),
    ...(query.attributes ? { attributes: query.attributes } : {}),
    merchantId: query.merchantId,
    minPrice: query.minPrice,
    maxPrice: query.maxPrice,
    sort: query.sort === 'default' ? null : query.sort,
    page: query.page || null,
  };
}

export function catalogSearchParams(query: CatalogQuery, size: number): Record<string, string | number> {
  const { q, sort, page, merchantId, minPrice, maxPrice } = query;
  const params: Record<string, string | number> = { q, sort, page, size, includeSkuFacets: 'true' };
  if (query.match !== 'all') params['match'] = query.match;
  if (query.exclude) params['exclude'] = query.exclude;
  if (query.sku) params['sku'] = query.sku.trim();
  if (query.attributes) params['attributes'] = query.attributes;
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

export function invalidCatalogSku(value = ''): boolean {
  return invalidCatalogSelection(value, 4);
}

export function invalidCatalogAttributes(value = ''): boolean {
  return invalidCatalogSelection(value, 6);
}

function invalidCatalogSelection(value: string, limit: number): boolean {
  if (!value.trim()) return false;
  const fields = value.split(';');
  const names = fields.map((field) => field.split('=', 1)[0].trim().toLowerCase());
  return (
    value.length > 360 ||
    fields.length > limit ||
    new Set(names).size !== fields.length ||
    fields.some((field) => !/^\s*[^=;\s][^=;]*=\s*\S.*$/u.test(field))
  );
}

export function readPositiveId(value: string | null): number | null {
  const number = Number(value);
  return value !== null && /^\d+$/.test(value) && Number.isSafeInteger(number) && number > 0 ? number : null;
}

function readPrice(value: string | null): number | null {
  const number = Number(value);
  return value?.trim() && Number.isFinite(number) && number >= 0 ? number : null;
}
