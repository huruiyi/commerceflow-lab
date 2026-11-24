export interface CreateOrderRequest {
  productId: number;
  skuId?: number;
  note: string;
  quantity: number;
}
