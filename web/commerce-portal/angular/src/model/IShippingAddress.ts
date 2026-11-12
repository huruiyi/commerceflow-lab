export interface ShippingAddress {
  id: number;
  recipientName: string;
  phone: string;
  countryCode: string;
  stateProvince: string;
  city: string;
  district: string;
  addressLine1: string;
  addressLine2: string;
  postalCode: string;
  label: string;
  isDefault: boolean;
}

export type ShippingAddressRequest = Omit<ShippingAddress, 'id'>;
