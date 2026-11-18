package vip.fairy.commerceflow.payment.infrastructure.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface RefundRequestMapper {

  List<Map<String,Object>> latestRefund(@Param("orderId") long orderId);

  List<Map<String,Object>> lockCommandInbox(@Param("commandId") String commandId);

  List<Map<String,Object>> lockRefundByPayment(@Param("paymentId") Object paymentId);

  int insertRefundInbox(@Param("commandId") String commandId, @Param("fingerprint") String fingerprint);

  int insertRefund(
    @Param("refundNumber") String refundNumber,
    @Param("orderId") long orderId,
    @Param("paymentId") Object paymentId,
    @Param("customerId") long customerId,
    @Param("idempotencyKey") String idempotencyKey,
    @Param("fingerprint") String fingerprint,
    @Param("amount") Object amount,
    @Param("currency") Object currency,
    @Param("reasonCode") String reasonCode,
    @Param("reasonDetail") String reasonDetail,
    @Param("orderStatus") String orderStatus);

  Long findRefundId(@Param("refundNumber") String refundNumber);

  int insertRefundRequestedEvent(
    @Param("refundId") long refundId,
    @Param("eventId") String eventId,
    @Param("type") String type,
    @Param("reasonCode") String reasonCode);

  int finishRefundInbox(@Param("response") String response, @Param("commandId") String commandId);

  int insertRefundRequestedOutbox(
    @Param("eventId") String eventId,
    @Param("refundNumber") String refundNumber,
    @Param("payload") String payload);

  List<Map<String,Object>> lockSuccessfulPayment(@Param("orderId") long orderId);
}
