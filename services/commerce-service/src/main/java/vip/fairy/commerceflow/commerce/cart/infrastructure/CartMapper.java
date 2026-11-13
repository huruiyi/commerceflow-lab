package vip.fairy.commerceflow.commerce.cart.infrastructure;

import vip.fairy.commerceflow.commerce.cart.domain.OrderView;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

public interface CartMapper {

  void ensureCart(Long userId);

  Long cartId(Long userId);

  void add(@Param("cartId") Long cartId, @Param("productId") Long productId, @Param("quantity") int quantity);

  List<OrderView> list(Long userId);

  Optional<OrderView> view(@Param("userId") Long userId, @Param("itemId") Long itemId);

  int quantity(@Param("userId") Long userId, @Param("itemId") Long itemId, @Param("quantity") int quantity);

  int remove(@Param("userId") Long userId, @Param("itemId") Long itemId);

  int clear(Long userId);

  int selectAddress(@Param("userId") Long userId, @Param("addressId") Long addressId);

  Long selectedAddressId(Long userId);
}
