export interface OrderList {
  id: number;
  productId: number;
  createdAt: string;
  productName: string;
  price: number;
  quantity: number;
  totalPrice: number;
  thumbnailUrl: string;
  note: string;
}
