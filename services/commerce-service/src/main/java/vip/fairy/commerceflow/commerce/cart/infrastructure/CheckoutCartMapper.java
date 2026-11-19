package vip.fairy.commerceflow.commerce.cart.infrastructure;

import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.commerce.cart.application.contract.v1.RejectedCheckoutCartV1;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutAddressSnapshot;
import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;

import java.util.List;

public interface CheckoutCartMapper {

  Long lockCart(Long customerId);

  List<CheckoutCartItem> lockItems(Long customerId);

  CheckoutAddressSnapshot selectedAddress(Long customerId);

  CheckoutAddressSnapshot address(@Param("customerId") Long customerId, @Param("addressId") Long addressId);

  int removeItems(@Param("customerId") Long customerId, @Param("itemIds") List<Long> itemIds);

  void restoreMissing(@Param("customerId") Long customerId, @Param("items") List<RejectedCheckoutCartV1.Item> items);
}
