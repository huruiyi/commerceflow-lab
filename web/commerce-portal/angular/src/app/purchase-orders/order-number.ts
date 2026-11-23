export function displayOrderNumber(orderNumber: string): string {
  return /^ORD-[0-9a-f]{32}$/i.test(orderNumber) ? 'ORD-…' + orderNumber.slice(-8) : orderNumber;
}
