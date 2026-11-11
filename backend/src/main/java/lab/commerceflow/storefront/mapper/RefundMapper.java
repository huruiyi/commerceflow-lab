package lab.commerceflow.storefront.mapper;

import lab.commerceflow.storefront.domain.PaymentView;
import lab.commerceflow.storefront.domain.RefundEventView;
import lab.commerceflow.storefront.domain.RefundView;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.*;

public interface RefundMapper {
  @Select("SELECT p.*,m.merchant_no FROM payment_records p LEFT JOIN payment_merchant_accounts m ON m.id=p.merchant_account_id WHERE p.order_id=#{orderId} AND p.status='SUCCESS' ORDER BY p.id DESC LIMIT 1 FOR UPDATE")
  Optional<PaymentView> lockSuccessfulPayment(Long orderId);
  @Select("SELECT * FROM refund_records WHERE payment_id=#{paymentId}") Optional<RefundView> byPayment(Long paymentId);
  @Select("SELECT * FROM refund_records WHERE payment_id=#{paymentId} AND idempotency_key=#{key}") Optional<RefundView> byKey(@Param("paymentId")Long paymentId,@Param("key")String key);
  @Select("SELECT * FROM refund_records WHERE order_id=#{orderId} AND customer_id=#{customerId} ORDER BY id DESC LIMIT 1") Optional<RefundView> byCustomerOrder(@Param("customerId")Long customerId,@Param("orderId")Long orderId);
  @Select("SELECT * FROM refund_records WHERE refund_number=#{number} AND customer_id=#{customerId}") Optional<RefundView> byCustomerNumber(@Param("customerId")Long customerId,@Param("number")String number);
  @Select("SELECT * FROM refund_records WHERE status='PROCESSING' AND requested_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 30 SECOND) ORDER BY id LIMIT 50") List<RefundView> pendingReconciliation();
  @Select("SELECT * FROM refund_records WHERE order_id=#{orderId} ORDER BY id DESC LIMIT 1") Optional<RefundView> byOrder(Long orderId);
  @Select("SELECT * FROM refund_records WHERE channel_refund_no=#{channelRefundNo} FOR UPDATE") Optional<RefundView> lockByChannelRefundNo(String channelRefundNo);
  @Select("SELECT * FROM refund_records WHERE refund_number=#{refundNumber} FOR UPDATE") Optional<RefundView> byOrderNumberForUpdate(String refundNumber);
  @Select("SELECT p.*,m.merchant_no FROM payment_records p LEFT JOIN payment_merchant_accounts m ON m.id=p.merchant_account_id WHERE p.id=#{paymentId}") Optional<PaymentView> paymentById(Long paymentId);
  @Insert("INSERT INTO refund_records(refund_number,order_id,payment_id,customer_id,idempotency_key,request_fingerprint,amount,currency,reason_code,reason_detail,status,order_status_before_refund) VALUES(#{number},#{orderId},#{paymentId},#{customerId},#{key},#{fingerprint},#{amount},#{currency},#{reasonCode},#{reasonDetail},'PROCESSING',#{previousStatus})")
  void insert(@Param("number")String number,@Param("orderId")Long orderId,@Param("paymentId")Long paymentId,@Param("customerId")Long customerId,@Param("key")String key,@Param("fingerprint")String fingerprint,@Param("amount")BigDecimal amount,@Param("currency")String currency,@Param("reasonCode")String reasonCode,@Param("reasonDetail")String reasonDetail,@Param("previousStatus")String previousStatus);
  @Update("UPDATE refund_records SET channel_refund_no=#{channelRefundNo},updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=#{id} AND status='PROCESSING' AND channel_refund_no IS NULL") int attachChannel(@Param("id")Long id,@Param("channelRefundNo")String channelRefundNo);
  @Update("UPDATE refund_records SET status='SUCCEEDED',channel_refund_no=#{channelRefundNo},failure_reason='',succeeded_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=#{id} AND status='PROCESSING'") int succeed(@Param("id")Long id,@Param("channelRefundNo")String channelRefundNo);
  @Update("UPDATE refund_records SET status='FAILED',channel_refund_no=#{channelRefundNo},failure_reason=#{reason},failed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=#{id} AND status='PROCESSING'") int fail(@Param("id")Long id,@Param("channelRefundNo")String channelRefundNo,@Param("reason")String reason);
  @Update("UPDATE payment_records SET refunded_amount=refunded_amount+#{amount},updated_at=CURRENT_TIMESTAMP,version=version+1 WHERE id=#{paymentId} AND status='SUCCESS' AND refunded_amount+#{amount}<=amount") int addRefundedAmount(@Param("paymentId")Long paymentId,@Param("amount")BigDecimal amount);
  @Insert("INSERT INTO refund_events(refund_id,event_id,event_type,from_status,to_status,detail) VALUES(#{refundId},#{eventId},#{type},#{fromStatus},#{toStatus},#{detail})") void insertEvent(@Param("refundId")Long refundId,@Param("eventId")String eventId,@Param("type")String type,@Param("fromStatus")String fromStatus,@Param("toStatus")String toStatus,@Param("detail")String detail);
  @Select("SELECT id,event_type,from_status,to_status,detail,created_at FROM refund_events WHERE refund_id=#{refundId} ORDER BY created_at,id") List<RefundEventView> events(Long refundId);
  @Insert("INSERT INTO refund_notifications(refund_id,channel,notification_id,channel_refund_no,signature_valid,processing_status,payload_digest,failure_reason,processed_at) VALUES(#{refundId},#{channel},#{notificationId},#{channelRefundNo},TRUE,#{processingStatus},#{digest},#{reason},CURRENT_TIMESTAMP)") void insertNotification(@Param("refundId")Long refundId,@Param("channel")String channel,@Param("notificationId")String notificationId,@Param("channelRefundNo")String channelRefundNo,@Param("processingStatus")String processingStatus,@Param("digest")String digest,@Param("reason")String reason);
}
