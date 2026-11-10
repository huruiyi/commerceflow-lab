package com.tanerinal.education.mapper;

import com.tanerinal.education.domain.*;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.*;

public interface ShopOrderMapper {
  @Select("""
      SELECT i.product_id,p.merchant_id,p.name AS product_name,p.thumbnail_url,p.price AS unit_price,i.quantity
      FROM cart_items i JOIN carts c ON c.id=i.cart_id JOIN products p ON p.id=i.product_id
      WHERE c.user_id=#{userId} ORDER BY i.id FOR UPDATE
      """) List<CheckoutCartItem> lockCartItems(Long userId);

  @Select("""
      SELECT a.* FROM shipping_addresses a JOIN carts c ON c.selected_address_id=a.id
      WHERE c.user_id=#{userId} AND a.user_id=#{userId}
      """) ShippingAddress selectedAddress(Long userId);

  @Insert("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,created_at,updated_at) VALUES(#{orderNumber},#{customerId},'PENDING_PAYMENT',#{totalAmount},#{currency},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
  @Options(useGeneratedKeys=true,keyProperty="id") int insertOrder(NewShopOrder order);

  @Insert("INSERT INTO order_items(order_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,subtotal) VALUES(#{orderId},#{item.productId},#{item.merchantId},#{item.productName},#{item.thumbnailUrl},#{item.unitPrice},#{item.quantity},#{subtotal})")
  void insertItem(@Param("orderId") Long orderId,@Param("item") CheckoutCartItem item,@Param("subtotal") BigDecimal subtotal);

  @Insert("""
      INSERT INTO order_shipping_addresses(order_id,recipient_name,phone,country_code,state_province,city,district,address_line1,address_line2,postal_code,label)
      VALUES(#{orderId},#{a.recipientName},#{a.phone},#{a.countryCode},#{a.stateProvince},#{a.city},#{a.district},#{a.addressLine1},#{a.addressLine2},#{a.postalCode},#{a.label})
      """) void insertAddress(@Param("orderId") Long orderId,@Param("a") ShippingAddress address);

  @Insert("INSERT INTO order_events(order_id,actor_id,event_type,from_status,to_status,detail,created_at) VALUES(#{orderId},#{actorId},#{type},#{fromStatus},#{toStatus},#{detail},CURRENT_TIMESTAMP)")
  void insertEvent(@Param("orderId")Long orderId,@Param("actorId")Long actorId,@Param("type")String type,@Param("fromStatus")String fromStatus,@Param("toStatus")String toStatus,@Param("detail")String detail);

  @Select("SELECT * FROM shop_orders WHERE customer_id=#{customerId} ORDER BY created_at DESC,id DESC") List<ShopOrderView> list(Long customerId);
  @Select("SELECT * FROM shop_orders WHERE id=#{id} AND customer_id=#{customerId}") Optional<ShopOrderView> find(@Param("customerId")Long customerId,@Param("id")Long id);
  @Select("SELECT * FROM shop_orders WHERE id=#{id} AND customer_id=#{customerId} FOR UPDATE") Optional<ShopOrderView> lock(@Param("customerId")Long customerId,@Param("id")Long id);
  @Select("SELECT * FROM order_items WHERE order_id=#{orderId} ORDER BY id") List<OrderItemView> items(Long orderId);
  @Select("SELECT * FROM order_shipping_addresses WHERE order_id=#{orderId}") OrderAddressView address(Long orderId);

  @Select("SELECT * FROM payment_records WHERE idempotency_key=#{key}") Optional<PaymentView> paymentByKey(String key);
  @Select("SELECT * FROM payment_records WHERE order_id=#{orderId} ORDER BY id DESC LIMIT 1") Optional<PaymentView> latestPayment(Long orderId);
  @Insert("INSERT INTO payment_records(order_id,payment_number,idempotency_key,amount,status,failure_reason,created_at) VALUES(#{orderId},#{number},#{key},#{amount},#{status},#{reason},CURRENT_TIMESTAMP)")
  void insertPayment(@Param("orderId")Long orderId,@Param("number")String number,@Param("key")String key,@Param("amount")BigDecimal amount,@Param("status")String status,@Param("reason")String reason);
  @Update("UPDATE shop_orders SET status=#{toStatus},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status=#{fromStatus}")
  int updateStatus(@Param("id")Long id,@Param("fromStatus")String fromStatus,@Param("toStatus")String toStatus);
}
