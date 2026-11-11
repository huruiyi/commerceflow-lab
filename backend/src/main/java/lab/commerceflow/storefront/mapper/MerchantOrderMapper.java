package lab.commerceflow.storefront.mapper;
import lab.commerceflow.storefront.domain.ShopOrderView;import java.util.*;import org.apache.ibatis.annotations.*;
public interface MerchantOrderMapper{
 @Select("SELECT DISTINCT o.* FROM shop_orders o JOIN order_items i ON i.order_id=o.id WHERE i.merchant_id=#{merchantId} ORDER BY o.created_at DESC")List<ShopOrderView> list(Long merchantId);
 @Select("SELECT DISTINCT o.* FROM shop_orders o JOIN order_items i ON i.order_id=o.id WHERE o.id=#{orderId} AND i.merchant_id=#{merchantId} FOR UPDATE")Optional<ShopOrderView> lock(@Param("merchantId")Long merchantId,@Param("orderId")Long orderId);
 @Update("UPDATE shop_orders SET status='CONFIRMED',confirmed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=#{orderId} AND status='PAID'")int confirm(Long orderId);
 @Update("UPDATE shop_orders SET status='SHIPPED',carrier=#{carrier},tracking_number=#{tracking},shipped_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=#{orderId} AND status='CONFIRMED'")int ship(@Param("orderId")Long orderId,@Param("carrier")String carrier,@Param("tracking")String tracking);
 @Update("UPDATE shop_orders SET carrier=#{carrier},tracking_number=#{tracking},updated_at=CURRENT_TIMESTAMP WHERE id=#{orderId} AND status='SHIPPED'")int updateShipment(@Param("orderId")Long orderId,@Param("carrier")String carrier,@Param("tracking")String tracking);
}
