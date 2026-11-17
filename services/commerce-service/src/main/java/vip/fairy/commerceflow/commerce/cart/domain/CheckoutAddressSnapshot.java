package vip.fairy.commerceflow.commerce.cart.domain;

public record CheckoutAddressSnapshot(
  /** Recipient name selected at checkout. */
  String recipientName,
  /** Recipient contact phone selected at checkout. */
  String phone,
  /** ISO 3166-1 alpha-2 destination country code. */
  String countryCode,
  /** Destination province or state. */
  String stateProvince,
  /** Destination city. */
  String city,
  /** Destination district or county. */
  String district,
  /** Primary delivery address line. */
  String addressLine1,
  /** Optional secondary delivery address line. */
  String addressLine2,
  /** Destination postal code. */
  String postalCode,
  /** Customer-defined address label captured at checkout. */
  String label
) {
}
