package vip.fairy.commerceflow.commerce.customer.api;

import vip.fairy.commerceflow.commerce.customer.domain.ShippingAddress;

public record ShippingAddressResponse(Long id, String recipientName, String phone, String countryCode,
                                      String stateProvince, String city, String district, String addressLine1,
                                      String addressLine2,
                                      String postalCode, String label, boolean isDefault) {
  public static ShippingAddressResponse from(ShippingAddress a) {
    return new ShippingAddressResponse(a.getId(), a.getRecipientName(), a.getPhone(), a.getCountryCode(),
      a.getStateProvince(), a.getCity(), a.getDistrict(), a.getAddressLine1(), a.getAddressLine2(),
      a.getPostalCode(), a.getLabel(), a.isDefault());
  }
}
