package vip.fairy.commerceflow.commerce.cart.infrastructure.adapter;

import vip.fairy.commerceflow.commerce.cart.application.CartService;
import vip.fairy.commerceflow.commerce.cart.application.contract.v1.RejectedCheckoutCartV1;
import vip.fairy.commerceflow.commerce.cart.application.port.CheckoutCartPort;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutAddressSnapshot;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CheckoutCartMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocalCheckoutCartAdapter implements CheckoutCartPort, RejectedCheckoutCartV1 {

  private final CheckoutCartMapper checkout;
  private final CartService carts;

  public LocalCheckoutCartAdapter(CheckoutCartMapper checkout, CartService carts) {
    this.checkout = checkout;
    this.carts = carts;
  }

  @Override
  public List<CheckoutCartItem> lockItems(Long customerId) {
    return checkout.lockItems(customerId);
  }

  @Override
  public List<CheckoutCartItem> priceItems(List<CheckoutCartItem> items) {
    return carts.priceItems(items);
  }

  @Override
  public CheckoutAddressSnapshot selectedAddress(Long customerId) {
    return checkout.selectedAddress(customerId);
  }

  @Override
  public CheckoutAddressSnapshot address(Long customerId, Long addressId) {
    return checkout.address(customerId, addressId);
  }

  @Override
  public int removeItems(Long customerId, List<Long> itemIds) {
    return checkout.removeItems(customerId, itemIds);
  }

  @Override
  public void restoreMissing(Long customerId, List<RejectedCheckoutCartV1.Item> items) {
    checkout.restoreMissing(customerId, items);
  }
}
