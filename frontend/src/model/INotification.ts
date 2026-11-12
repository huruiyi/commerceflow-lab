export interface CustomerNotification {
  id: number;
  orderId?: number;
  notificationType: string;
  title: string;
  message: string;
  status: 'UNREAD' | 'READ';
  readAt?: string;
  createdAt: string;
}
