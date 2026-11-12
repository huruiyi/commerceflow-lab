export interface MerchantInventoryItem {
  productId: number;
  productName: string;
  thumbnailUrl: string;
  price: number;
  stockAvailable: number;
  stockReserved: number;
  stockSold: number;
}

export interface InventoryMovement {
  id: number;
  orderId?: number;
  productId: number;
  movementType: string;
  quantity: number;
  availableAfter: number;
  reservedAfter: number;
  soldAfter: number;
  detail: string;
  createdAt: string;
}
