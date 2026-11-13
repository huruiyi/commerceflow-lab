package vip.fairy.commerceflow.commerce.refund.infrastructure;

import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundEventView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundView;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

public interface RefundMapper {

  Optional<PaymentView> lockSuccessfulPayment(Long orderId);

  Optional<RefundView> byPayment(Long paymentId);

  Optional<RefundView> byKey(@Param("paymentId") Long paymentId, @Param("key") String key);

  Optional<RefundView> byCustomerOrder(@Param("customerId") Long customerId, @Param("orderId") Long orderId);

  Optional<RefundView> byCustomerNumber(@Param("customerId") Long customerId, @Param("number") String number);

  List<RefundView> pendingReconciliation();

  Optional<RefundView> byOrder(Long orderId);

  Optional<RefundView> lockByChannelRefundNo(String channelRefundNo);

  Optional<RefundView> byOrderNumberForUpdate(String refundNumber);

  Optional<PaymentView> paymentById(Long paymentId);

  void insert(
    @Param("number") String number,
    @Param("orderId") Long orderId,
    @Param("paymentId") Long paymentId,
    @Param("customerId") Long customerId,
    @Param("key") String key,
    @Param("fingerprint") String fingerprint,
    @Param("amount") BigDecimal amount,
    @Param("currency") String currency,
    @Param("reasonCode") String reasonCode,
    @Param("reasonDetail") String reasonDetail,
    @Param("previousStatus") String previousStatus
  );

  int attachChannel(@Param("id") Long id, @Param("channelRefundNo") String channelRefundNo);

  int succeed(@Param("id") Long id, @Param("channelRefundNo") String channelRefundNo);

  int fail(@Param("id") Long id, @Param("channelRefundNo") String channelRefundNo, @Param("reason") String reason);

  int addRefundedAmount(@Param("paymentId") Long paymentId, @Param("amount") BigDecimal amount);

  void insertEvent(
    @Param("refundId") Long refundId,
    @Param("eventId") String eventId,
    @Param("type") String type,
    @Param("fromStatus") String fromStatus,
    @Param("toStatus") String toStatus,
    @Param("detail") String detail
  );

  List<RefundEventView> events(Long refundId);

  void insertNotification(
    @Param("refundId") Long refundId,
    @Param("channel") String channel,
    @Param("notificationId") String notificationId,
    @Param("channelRefundNo") String channelRefundNo,
    @Param("processingStatus") String processingStatus,
    @Param("digest") String digest,
    @Param("reason") String reason
  );
}
