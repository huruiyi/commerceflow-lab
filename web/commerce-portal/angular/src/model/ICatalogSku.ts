export interface CatalogSku {
  skuId: number;
  productId: number;
  code: string;
  specifications: Record<string, string>;
  defaultSku: boolean;
  unitPrice: number;
  status: 'ACTIVE' | 'INACTIVE';
  businessVersion: number;
  stockProductId: number | null;
}
