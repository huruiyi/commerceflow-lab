package lab.commerceflow.storefront.mapper;

import lab.commerceflow.storefront.domain.OrderView;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Optional;

public interface CartMapper {
  @Insert("INSERT INTO carts(user_id, created_at, updated_at) VALUES(#{userId}, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE updated_at=updated_at")
  void ensureCart(Long userId);

  @Select("SELECT id FROM carts WHERE user_id=#{userId}")
  Long cartId(Long userId);

  @Insert("INSERT INTO cart_items(cart_id, product_id, quantity, created_at, updated_at) VALUES(#{cartId},#{productId},#{quantity},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE quantity=LEAST(quantity + #{quantity},99), updated_at=CURRENT_TIMESTAMP")
  void add(@Param("cartId") Long cartId, @Param("productId") Long productId, @Param("quantity") int quantity);

  @Select("""
      SELECT i.id, i.product_id, i.updated_at AS created_at, '' AS note, i.quantity,
             p.name AS product_name, p.price, p.thumbnail_url
      FROM cart_items i JOIN carts c ON c.id=i.cart_id JOIN products p ON p.id=i.product_id
      WHERE c.user_id=#{userId} ORDER BY i.created_at, i.id
      """)
  List<OrderView> list(Long userId);

  @Select("""
      SELECT i.id, i.product_id, i.updated_at AS created_at, '' AS note, i.quantity,
             p.name AS product_name, p.price, p.thumbnail_url
      FROM cart_items i JOIN carts c ON c.id=i.cart_id JOIN products p ON p.id=i.product_id
      WHERE c.user_id=#{userId} AND i.id=#{itemId}
      """)
  Optional<OrderView> view(@Param("userId") Long userId, @Param("itemId") Long itemId);

  @Update("UPDATE cart_items SET quantity=#{quantity},updated_at=CURRENT_TIMESTAMP WHERE id=#{itemId} AND cart_id IN (SELECT id FROM carts WHERE user_id=#{userId})")
  int quantity(@Param("userId") Long userId, @Param("itemId") Long itemId, @Param("quantity") int quantity);

  @Delete("DELETE FROM cart_items WHERE id=#{itemId} AND cart_id IN (SELECT id FROM carts WHERE user_id=#{userId})")
  int remove(@Param("userId") Long userId, @Param("itemId") Long itemId);

  @Delete("DELETE FROM cart_items WHERE cart_id IN (SELECT id FROM carts WHERE user_id=#{userId})")
  int clear(Long userId);

  @Update("UPDATE carts SET selected_address_id=#{addressId},updated_at=CURRENT_TIMESTAMP WHERE user_id=#{userId}")
  int selectAddress(@Param("userId") Long userId, @Param("addressId") Long addressId);

  @Select("SELECT selected_address_id FROM carts WHERE user_id=#{userId}")
  Long selectedAddressId(Long userId);
}
