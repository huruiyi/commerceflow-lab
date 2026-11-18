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
export interface StocktakeBaseline {
  productId: number;
  available: number;
  reserved: number;
  sold: number;
  movementId: number;
  movementAt: string | null;
}

export interface StocktakeProposal {
  reference: string;
  productId: number;
  baseline: StocktakeBaseline;
  countedAvailable: number;
  countedAt: string;
  note: string;
  commandIds: string[];
}

export interface StocktakeReceipt {
  reference: string;
  movementId: number | null;
  adjustment: number;
}

export interface StocktakeReview {
  proposal: StocktakeProposal;
  countedBy: string;
  approvedBy: string | null;
  receipt: StocktakeReceipt | null;
}
