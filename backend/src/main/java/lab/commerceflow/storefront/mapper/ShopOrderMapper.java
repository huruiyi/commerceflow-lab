package lab.commerceflow.storefront.mapper;

import lab.commerceflow.storefront.domain.*;
import org.apache.ibatis.annotations.*;
import java.math.BigDecimal;
import java.util.*;

public interface ShopOrderMapper {
  @Select("SELECT status FROM shop_orders WHERE id=#{id}") String status(Long id);
  @Update("UPDATE shop_orders SET logistics_delivered_at=COALESCE(logistics_delivered_at,#{deliveredAt}),updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status IN ('SHIPPED','RECEIVED','COMPLETED')") int markLogisticsDelivered(@Param("id")Long id,@Param("deliveredAt")java.time.Instant deliveredAt);
  @Select("""
      SELECT i.product_id,p.merchant_id,p.name AS product_name,p.thumbnail_url,p.price AS unit_price,i.quantity
      FROM cart_items i JOIN carts c ON c.id=i.cart_id JOIN products p ON p.id=i.product_id
      WHERE c.user_id=#{userId} ORDER BY i.id FOR UPDATE
      """) List<CheckoutCartItem> lockCartItems(Long userId);

  @Select("""
      SELECT a.* FROM shipping_addresses a JOIN carts c ON c.selected_address_id=a.id
      WHERE c.user_id=#{userId} AND a.user_id=#{userId}
      """) ShippingAddress selectedAddress(Long userId);

  @Insert("INSERT INTO shop_orders(order_number,customer_id,status,total_amount,currency,payment_due_at,created_at,updated_at) VALUES(#{orderNumber},#{customerId},'PENDING_PAYMENT',#{totalAmount},#{currency},#{paymentDueAt},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
  @Options(useGeneratedKeys=true,keyProperty="id") int insertOrder(NewShopOrder order);

  @Insert("INSERT INTO order_items(order_id,product_id,merchant_id,product_name,thumbnail_url,unit_price,quantity,subtotal) VALUES(#{orderId},#{item.productId},#{item.merchantId},#{item.productName},#{item.thumbnailUrl},#{item.unitPrice},#{item.quantity},#{subtotal})")
  void insertItem(@Param("orderId") Long orderId,@Param("item") CheckoutCartItem item,@Param("subtotal") BigDecimal subtotal);

  @Insert("""
      INSERT INTO order_shipping_addresses(order_id,recipient_name,phone,country_code,state_province,city,district,address_line1,address_line2,postal_code,label)
      VALUES(#{orderId},#{a.recipientName},#{a.phone},#{a.countryCode},#{a.stateProvince},#{a.city},#{a.district},#{a.addressLine1},#{a.addressLine2},#{a.postalCode},#{a.label})
      """) void insertAddress(@Param("orderId") Long orderId,@Param("a") ShippingAddress address);

  default void insertEvent(Long orderId, Long actorId, String type, String fromStatus,
                           String toStatus, String detail) {
    insertEventDetailed(orderId, actorId, type, fromStatus, toStatus, detail, "USER", null);
  }

  @Insert("INSERT INTO order_events(order_id,actor_id,event_type,from_status,to_status,detail,trigger_source,scheduled_task_id,created_at) VALUES(#{orderId},#{actorId},#{type},#{fromStatus},#{toStatus},#{detail},#{triggerSource},#{scheduledTaskId},CURRENT_TIMESTAMP)")
  void insertEventDetailed(@Param("orderId") Long orderId, @Param("actorId") Long actorId,
      @Param("type") String type, @Param("fromStatus") String fromStatus,
      @Param("toStatus") String toStatus, @Param("detail") String detail,
      @Param("triggerSource") String triggerSource,
      @Param("scheduledTaskId") Long scheduledTaskId);

  @Select("SELECT * FROM shop_orders WHERE customer_id=#{customerId} ORDER BY created_at DESC,id DESC") List<ShopOrderView> list(Long customerId);
  @Select("SELECT * FROM shop_orders WHERE id=#{id} AND customer_id=#{customerId}") Optional<ShopOrderView> find(@Param("customerId")Long customerId,@Param("id")Long id);
  @Select("SELECT * FROM shop_orders WHERE id=#{id} AND customer_id=#{customerId} FOR UPDATE") Optional<ShopOrderView> lock(@Param("customerId")Long customerId,@Param("id")Long id);
  @Select("SELECT * FROM shop_orders WHERE id=#{id} FOR UPDATE") Optional<ShopOrderView> lockById(Long id);
  @Select("SELECT * FROM order_items WHERE order_id=#{orderId} ORDER BY id") List<OrderItemView> items(Long orderId);
  @Select("SELECT * FROM order_shipping_addresses WHERE order_id=#{orderId}") OrderAddressView address(Long orderId);
  @Select("SELECT id,actor_id,event_type,from_status,to_status,detail,trigger_source,scheduled_task_id,created_at FROM order_events WHERE order_id=#{orderId} ORDER BY created_at,id") List<OrderEventView> events(Long orderId);

  @Select("SELECT * FROM payment_merchant_accounts WHERE channel=#{channel} AND enabled=TRUE")
  Optional<PaymentMerchantAccount> merchantAccount(PaymentChannel channel);
  @Insert("INSERT INTO payment_idempotency_locks(idempotency_key,created_at) VALUES(#{key},CURRENT_TIMESTAMP) ON DUPLICATE KEY UPDATE idempotency_key=idempotency_key")
  void ensurePaymentIdempotencyKey(String key);
  @Select("SELECT idempotency_key FROM payment_idempotency_locks WHERE idempotency_key=#{key} FOR UPDATE")
  String lockPaymentIdempotencyKey(String key);
  @Select("SELECT p.*,m.merchant_no FROM payment_records p LEFT JOIN payment_merchant_accounts m ON m.id=p.merchant_account_id WHERE p.idempotency_key=#{key}")
  Optional<PaymentView> paymentByKey(String key);
  @Select("SELECT p.*,m.merchant_no FROM payment_records p LEFT JOIN payment_merchant_accounts m ON m.id=p.merchant_account_id WHERE p.order_id=#{orderId} ORDER BY p.id DESC LIMIT 1")
  Optional<PaymentView> latestPayment(Long orderId);
  @Select("SELECT p.*,m.merchant_no FROM payment_records p LEFT JOIN payment_merchant_accounts m ON m.id=p.merchant_account_id WHERE p.order_id=#{orderId} AND p.status='PENDING' ORDER BY p.id")
  List<PaymentView> pendingPayments(Long orderId);
  @Select("SELECT COUNT(*) FROM payment_records WHERE order_id=#{orderId} AND status='SUCCESS'")
  int successfulPaymentCount(Long orderId);
  @Insert("""
      INSERT INTO payment_records(order_id,merchant_account_id,payment_number,idempotency_key,channel,currency,
        request_fingerprint,channel_transaction_no,amount,status,failure_reason,paid_at,expires_at,created_at,updated_at)
      VALUES(#{orderId},#{merchantId},#{number},#{key},#{channel},#{currency},#{fingerprint},#{transactionNo},
        #{amount},#{status},#{reason},#{paidAt},#{expiresAt},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
      """)
  void insertPayment(@Param("orderId")Long orderId,@Param("merchantId")Long merchantId,
      @Param("number")String number,@Param("key")String key,@Param("channel")PaymentChannel channel,
      @Param("currency")String currency,@Param("fingerprint")String fingerprint,
      @Param("transactionNo")String transactionNo,@Param("amount")BigDecimal amount,
      @Param("status")String status,@Param("reason")String reason,
      @Param("paidAt")java.time.Instant paidAt,@Param("expiresAt")java.time.Instant expiresAt);
  @Insert("INSERT INTO payment_events(payment_id,event_id,event_type,from_status,to_status,detail,created_at) VALUES(#{paymentId},#{eventId},#{type},#{fromStatus},#{toStatus},#{detail},CURRENT_TIMESTAMP)")
  void insertPaymentEvent(@Param("paymentId")Long paymentId,@Param("eventId")String eventId,
      @Param("type")String type,@Param("fromStatus")String fromStatus,
      @Param("toStatus")String toStatus,@Param("detail")String detail);
  @Update("UPDATE payment_records SET checkout_url=#{checkoutUrl},action_type=#{actionType},qr_payload=#{qrPayload} WHERE id=#{id}") void updateCheckout(@Param("id")Long id,@Param("checkoutUrl")String checkoutUrl,@Param("actionType")String actionType,@Param("qrPayload")String qrPayload);
  @Select("SELECT p.*,m.merchant_no FROM payment_records p LEFT JOIN payment_merchant_accounts m ON m.id=p.merchant_account_id WHERE p.channel_transaction_no=#{transactionNo} FOR UPDATE") Optional<PaymentView> lockPaymentByTransaction(String transactionNo);
  @Insert("INSERT INTO payment_notifications(payment_id,channel,notification_id,channel_transaction_no,signature_valid,processing_status,payload_digest,failure_reason,raw_status,received_at,processed_at) VALUES(#{paymentId},#{channel},#{notificationId},#{transactionNo},TRUE,#{processingStatus},#{digest},#{reason},#{rawStatus},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)") void insertNotification(@Param("paymentId")Long paymentId,@Param("channel")String channel,@Param("notificationId")String notificationId,@Param("transactionNo")String transactionNo,@Param("processingStatus")String processingStatus,@Param("digest")String digest,@Param("reason")String reason,@Param("rawStatus")String rawStatus);
  @Update("UPDATE payment_records SET status=#{toStatus},failure_reason=#{reason},paid_at=CASE WHEN #{toStatus}='SUCCESS' THEN CURRENT_TIMESTAMP ELSE paid_at END,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=#{id} AND status='PENDING'") int finishPayment(@Param("id")Long id,@Param("toStatus")String toStatus,@Param("reason")String reason);
  @Update("UPDATE payment_records SET status='CLOSED',failure_reason=#{reason},updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=#{id} AND status='PENDING'")
  int closePendingPayment(@Param("id") Long id, @Param("reason") String reason);
  @Select("SELECT customer_id FROM shop_orders WHERE id=#{orderId}") Long orderCustomerId(Long orderId);
  @Update("UPDATE shop_orders SET status=#{toStatus},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status=#{fromStatus}")
  int updateStatus(@Param("id")Long id,@Param("fromStatus")String fromStatus,@Param("toStatus")String toStatus);
  @Update("UPDATE shop_orders SET status='CANCELLING',cancel_reason=#{reason},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='PENDING_PAYMENT'")
  int startCancellation(@Param("id") Long id, @Param("reason") String reason);
  @Update("UPDATE shop_orders SET status='CANCELLED',cancelled_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='CANCELLING'")
  int finishCancellation(Long id);
  @Update("UPDATE shop_orders SET status='PAID',cancel_reason=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='CANCELLING'")
  int restorePaidAfterCancellationRace(Long id);
  @Update("UPDATE shop_orders SET status='PAID',cancelled_at=NULL,cancel_reason='Recovered after late successful payment',updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='CANCELLED'")
  int restorePaidAfterLateSuccess(Long id);
  @Update("UPDATE shop_orders SET status='RECEIVED',received_at=#{receivedAt},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='SHIPPED'")
  int markReceived(@Param("id")Long id,@Param("receivedAt")java.time.Instant receivedAt);
  @Update("UPDATE shop_orders SET status='COMPLETED',completed_at=#{completedAt},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status='RECEIVED'")
  int markCompleted(@Param("id")Long id,@Param("completedAt")java.time.Instant completedAt);
}
