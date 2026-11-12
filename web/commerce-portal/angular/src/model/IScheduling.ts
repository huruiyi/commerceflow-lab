export interface SchedulingPolicy {
  taskType: string;
  enabled: boolean;
  primaryTrigger: 'DATABASE' | 'RABBITMQ' | 'ROCKETMQ';
  reconciliationEnabled: boolean;
  gracePeriodSeconds: number;
  claimLeaseSeconds: number;
  maxAttempts: number;
  retryDelaySeconds: number;
  version: number;
  source: string;
  handlerAvailable: boolean;
}

export interface ScheduledTask {
  id: number;
  taskKey: string;
  taskType: string;
  aggregateType: string;
  aggregateId: string;
  status: string;
  dueAt: string;
  primaryTriggerSnapshot: string;
  reconciliationEnabledSnapshot: boolean;
  attempts: number;
  maxAttempts: number;
  lastTriggerSource?: string;
  lastError?: string;
  createdAt: string;
}

export interface ScheduledTaskEvent {
  id: number;
  eventType: string;
  triggerSource: string;
  fromStatus?: string;
  toStatus?: string;
  detail: string;
  createdAt: string;
}

export interface SchedulingPolicyEvent {
  id: number;
  action: string;
  primaryTrigger: string;
  enabled: boolean;
  reconciliationEnabled: boolean;
  configVersion: number;
  operatedBy: string;
  createdAt: string;
}
