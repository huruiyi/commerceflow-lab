package vip.fairy.commerceflow.commerce.cart.application.port;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutAddressSnapshot;

import java.util.List;

public interface CheckoutCartPort {

  List<CheckoutCartItem> lockItems(Long customerId);

  CheckoutAddressSnapshot selectedAddress(Long customerId);

  CheckoutAddressSnapshot address(Long customerId, Long addressId);

  int removeItems(Long customerId, List<Long> itemIds);
}
