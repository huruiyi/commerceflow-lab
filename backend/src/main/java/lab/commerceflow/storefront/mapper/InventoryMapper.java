package lab.commerceflow.storefront.mapper;

import java.util.List;
import lab.commerceflow.storefront.domain.InventoryReservation;
import lab.commerceflow.storefront.domain.ProductInventory;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface InventoryMapper {
  @Update("""
      UPDATE products
      SET stock_available=stock_available-#{quantity},stock_reserved=stock_reserved+#{quantity}
      WHERE id=#{productId} AND stock_available>=#{quantity}
      """)
  int reserve(@Param("productId") Long productId, @Param("quantity") int quantity);

  @Insert("""
      INSERT INTO inventory_reservations(order_id,product_id,quantity,status)
      VALUES(#{orderId},#{productId},#{quantity},'RESERVED')
      """)
  void insertReservation(@Param("orderId") Long orderId, @Param("productId") Long productId,
                         @Param("quantity") int quantity);

  @Select("SELECT * FROM inventory_reservations WHERE order_id=#{orderId} ORDER BY id FOR UPDATE")
  List<InventoryReservation> lockByOrder(Long orderId);

  @Update("""
      UPDATE products
      SET stock_reserved=stock_reserved-#{quantity},stock_sold=stock_sold+#{quantity}
      WHERE id=#{productId} AND stock_reserved>=#{quantity}
      """)
  int commitReserved(@Param("productId") Long productId, @Param("quantity") int quantity);

  @Update("""
      UPDATE products
      SET stock_available=stock_available-#{quantity},stock_sold=stock_sold+#{quantity}
      WHERE id=#{productId} AND stock_available>=#{quantity}
      """)
  int commitReleased(@Param("productId") Long productId, @Param("quantity") int quantity);

  @Update("""
      UPDATE products
      SET stock_available=stock_available+#{quantity},stock_reserved=stock_reserved-#{quantity}
      WHERE id=#{productId} AND stock_reserved>=#{quantity}
      """)
  int release(@Param("productId") Long productId, @Param("quantity") int quantity);

  @Update("""
      UPDATE inventory_reservations
      SET status='COMMITTED',committed_at=CURRENT_TIMESTAMP,
        released_at=CASE WHEN #{fromStatus}='RELEASED' THEN NULL ELSE released_at END,
        version=version+1
      WHERE id=#{id} AND status=#{fromStatus}
      """)
  int markCommitted(@Param("id") Long id, @Param("fromStatus") String fromStatus);

  @Update("""
      UPDATE inventory_reservations
      SET status='RELEASED',released_at=CURRENT_TIMESTAMP,version=version+1
      WHERE id=#{id} AND status='RESERVED'
      """)
  int markReleased(Long id);

  @Select("SELECT id,stock_available,stock_reserved,stock_sold FROM products WHERE id=#{productId}")
  ProductInventory inventory(Long productId);

  @Insert("""
      INSERT INTO inventory_movements(event_id,order_id,product_id,movement_type,quantity,
        available_after,reserved_after,sold_after,detail)
      VALUES(#{eventId},#{orderId},#{productId},#{movementType},#{quantity},
        #{inventory.stockAvailable},#{inventory.stockReserved},#{inventory.stockSold},#{detail})
      """)
  void insertMovement(@Param("eventId") String eventId, @Param("orderId") Long orderId,
      @Param("productId") Long productId, @Param("movementType") String movementType,
      @Param("quantity") int quantity, @Param("inventory") ProductInventory inventory,
      @Param("detail") String detail);

  @Select("SELECT customer_id FROM shop_orders WHERE id=#{orderId}")
  Long customerId(Long orderId);

  @Insert("""
      INSERT INTO customer_notifications(event_id,customer_id,order_id,notification_type,title,message)
      VALUES(#{eventId},#{customerId},#{orderId},#{type},#{title},#{message})
      """)
  int insertNotification(@Param("eventId") String eventId, @Param("customerId") Long customerId,
      @Param("orderId") Long orderId, @Param("type") String type,
      @Param("title") String title, @Param("message") String message);
}
