export interface Session {
  userId: number;
  username: string;
  displayName: string;
  email: string;
  authorities: string[];
  passwordChangeRequired: boolean;
  mfaRequired: boolean;
  mfaEnabled: boolean;
}

export interface Merchant {
  id: number;
  merchantNo: string;
  displayName: string;
  remark: string;
  appId: string;
  secretMasked: string;
  credentialVersion: number;
  signingAlgorithm: 'HMAC_SHA256' | 'RSA_SHA256';
  merchantPublicKey?: string;
  notifyUrl?: string;
  enabled: boolean;
  createdBy: number;
  createdAt: string;
}

export interface IssuedCredential {
  merchant: Merchant;
  appSecret: string;
  warning: string;
}

export interface Transaction {
  canClose?: boolean;
  channelTransactionNo: string;
  merchantRequestNo: string;
  channel: string;
  subject: string;
  amount: number;
  currency: string;
  status: string;
  faultScenario: string;
  actionType: string;
  checkoutUrl: string;
  qrPayload?: string;
  expiresAt: string;
  paidAt?: string;
  closedAt?: string;
  createdAt: string;
}

export interface Refund {
  canRedeliver?: boolean;
  channelRefundNo: string;
  merchantRefundNo: string;
  channelTransactionNo: string;
  channel: string;
  amount: number;
  currency: string;
  reasonCode: string;
  status: string;
  faultScenario: string;
  failureReason: string;
  succeededAt?: string;
  failedAt?: string;
  createdAt: string;
}

export interface AdminUser {
  id: number;
  username: string;
  displayName: string;
  email: string;
  mobile?: string;
  status: string;
  accountLocked: boolean;
  passwordChangeRequired: boolean;
  mfaRequired: boolean;
  mfaEnabled: boolean;
  roles: string[];
  lastLoginAt?: string;
}

export type RoleScope = 'PLATFORM' | 'MERCHANT';

export interface PaymentAdminRole {
  id: number;
  code: string;
  name: string;
  scope: RoleScope;
  description: string;
  enabled: boolean;
  systemRole: boolean;
  permissions: string[];
  accountCount: number;
}

export interface PaymentRoleBinding {
  roleId: number;
  roleCode: string;
  roleName: string;
  roleScope: RoleScope;
  merchantId?: number | null;
  merchantName?: string | null;
  enabled: boolean;
  roleEnabled: boolean;
}

export interface AuditLog {
  id: number;
  actorUsername: string;
  action: string;
  targetType: string;
  targetId?: string;
  result: string;
  detail: string;
  sourceIp?: string;
  createdAt: string;
}
