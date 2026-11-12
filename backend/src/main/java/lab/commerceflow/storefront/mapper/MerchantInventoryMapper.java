package lab.commerceflow.storefront.mapper;

import java.util.List;
import lab.commerceflow.storefront.domain.InventoryMovementView;
import lab.commerceflow.storefront.domain.MerchantInventoryItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface MerchantInventoryMapper {
  @Select("""
      SELECT p.id AS product_id,p.name AS product_name,p.thumbnail_url,p.price,
        p.stock_available,p.stock_reserved,p.stock_sold
      FROM products p WHERE p.merchant_id=#{merchantId} ORDER BY p.id
      """)
  List<MerchantInventoryItem> list(Long merchantId);

  @Select("""
      SELECT p.id AS product_id,p.name AS product_name,p.thumbnail_url,p.price,
        p.stock_available,p.stock_reserved,p.stock_sold
      FROM products p WHERE p.id=#{productId} AND p.merchant_id=#{merchantId}
      """)
  MerchantInventoryItem find(@Param("merchantId") Long merchantId,
      @Param("productId") Long productId);

  @Update("""
      UPDATE products SET stock_available=stock_available+#{delta}
      WHERE id=#{productId} AND merchant_id=#{merchantId} AND stock_available+#{delta}>=0
      """)
  int adjust(@Param("merchantId") Long merchantId, @Param("productId") Long productId,
      @Param("delta") int delta);

  @Select("""
      SELECT m.id,m.order_id,m.product_id,m.movement_type,m.quantity,m.available_after,
        m.reserved_after,m.sold_after,m.detail,m.created_at
      FROM inventory_movements m JOIN products p ON p.id=m.product_id
      WHERE m.product_id=#{productId} AND p.merchant_id=#{merchantId}
      ORDER BY m.created_at DESC,m.id DESC LIMIT 100
      """)
  List<InventoryMovementView> movements(@Param("merchantId") Long merchantId,
      @Param("productId") Long productId);
}
