export interface InventoryRecovery {
  commandId: string;
  commandType: string;
  orderId: number;
  attempts: number;
  lastError: string | null;
  createdAt: string;
}

export interface WarehouseEvidenceContext {
  orderId: number;
  orderNumber: string;
  status: string;
  createdAt: string;
  deliveredAt: string;
  products: { productId: number; name: string; quantity: number }[];
}

export interface WarehouseEvidenceLine {
  productId: number;
  warehouseReference: string;
  evidenceAt: string;
  note: string;
  bookedMovementId?: number;
  expectedAvailable?: number;
}

export interface WarehouseEvidenceReceipt {
  orderId: number;
  movementIds: number[];
}
