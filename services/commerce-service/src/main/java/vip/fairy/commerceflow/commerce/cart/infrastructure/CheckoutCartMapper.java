package vip.fairy.commerceflow.commerce.cart.infrastructure;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutAddressSnapshot;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;

import java.util.List;

public interface CheckoutCartMapper {

  List<CheckoutCartItem> lockItems(Long customerId);

  CheckoutAddressSnapshot selectedAddress(Long customerId);
}
