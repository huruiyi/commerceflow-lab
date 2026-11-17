package vip.fairy.commerceflow.commerce.cart.infrastructure.adapter;

import vip.fairy.commerceflow.commerce.cart.application.port.CheckoutCartPort;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutAddressSnapshot;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CartMapper;
import vip.fairy.commerceflow.commerce.cart.infrastructure.CheckoutCartMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LocalCheckoutCartAdapter implements CheckoutCartPort {

  private final CheckoutCartMapper checkout;
  private final CartMapper carts;

  public LocalCheckoutCartAdapter(CheckoutCartMapper checkout, CartMapper carts) {
    this.checkout = checkout;
    this.carts = carts;
  }

  @Override
  public List<CheckoutCartItem> lockItems(Long customerId) {
    return checkout.lockItems(customerId);
  }

  @Override
  public CheckoutAddressSnapshot selectedAddress(Long customerId) {
    return checkout.selectedAddress(customerId);
  }

  @Override
  public void clear(Long customerId) {
    carts.clear(customerId);
  }
}
