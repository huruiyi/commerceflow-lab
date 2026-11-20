import {
  ClipboardList,
  CreditCard,
  FileClock,
  LayoutDashboard,
  Mail,
  MessageSquareText,
  PackageCheck,
  PackageOpen,
  Receipt,
  Settings,
  ShieldCheck,
  Store,
  Users,
  Wallet,
  Warehouse,
} from '@lucide/vue';

export const menuIcons = {
  dashboard: LayoutDashboard,
  card: CreditCard,
  refund: Receipt,
  wallet: Wallet,
  settings: Settings,
  merchant: Store,
  users: Users,
  audit: FileClock,
  security: ShieldCheck,
  messages: Mail,
  sms: MessageSquareText,
  email: Mail,
  warehouse: Warehouse,
  outbound: PackageCheck,
  returns: PackageOpen,
} as const;

export function menuIcon(name?: string) {
  return menuIcons[name as keyof typeof menuIcons] ?? ClipboardList;
}
