package vip.fairy.commerceflow.commerce.ordering.domain;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutAddressSnapshot;

public record OrderAddressSnapshot(
  /** Recipient name fixed when the order is created. */
  String recipientName,
  /** Recipient phone fixed when the order is created. */
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

  public static OrderAddressSnapshot from(CheckoutAddressSnapshot address) {
    return new OrderAddressSnapshot(address.recipientName(), address.phone(),
      address.countryCode(), address.stateProvince(), address.city(),
      address.district(), address.addressLine1(), address.addressLine2(),
      address.postalCode(), address.label());
  }
}
