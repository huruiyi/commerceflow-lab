export interface LogisticsCallbackOutbox {
  id: number;
  shipmentId: number;
  eventId: string;
  callbackUrl: string;
  status: string;
  attempts: number;
  maxAttempts: number;
  nextAttemptAt: string;
  leaseUntil?: string;
  claimedBy?: string;
  lastError?: string;
  createdAt: string;
  publishedAt?: string;
  updatedAt: string;
}

export interface LogisticsCallbackAuditEvent {
  id: number;
  eventType: string;
  fromStatus?: string;
  toStatus: string;
  operatedBy: string;
  reason?: string;
  createdAt: string;
}
