package vip.fairy.commerceflow.payment.infrastructure.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.RefundEvent;

@Mapper
public interface PaymentProtocolMapper {

  int ensureIdempotencyLock(@Param("key") String key);

  int insertPayment(
    @Param("orderId") long orderId,
    @Param("paymentNumber") String paymentNumber,
    @Param("key") String key,
    @Param("amount") BigDecimal amount,
    @Param("merchantId") Object merchantId,
    @Param("channel") String channel,
    @Param("currency") String currency,
    @Param("fingerprint") String fingerprint,
    @Param("channelTransactionNo") String channelTransactionNo,
    @Param("expiresAt") Timestamp expiresAt,
    @Param("checkoutUrl") String checkoutUrl);

  int insertCreateProcess(
    @Param("processId") String processId,
    @Param("orderId") long orderId,
    @Param("commandId") String commandId,
    @Param("fingerprint") String fingerprint,
    @Param("correlationId") String correlationId,
    @Param("causationId") String causationId,
    @Param("traceparent") String traceparent);

  int insertCreateCommand(
    @Param("commandId") String commandId,
    @Param("processId") String processId,
    @Param("orderId") long orderId,
    @Param("fingerprint") String fingerprint,
    @Param("payload") String payload,
    @Param("correlationId") String correlationId,
    @Param("causationId") String causationId,
    @Param("traceparent") String traceparent);

  List<Map<String,Object>> latestPayment(@Param("orderId") long orderId);

  List<Map<String,Object>> latestRefund(@Param("orderId") long orderId);

  List<Map<String,Object>> refundByNumber(@Param("refundNumber") String refundNumber);

  List<RefundEvent> refundEvents(@Param("refundId") long refundId);

  List<Map<String,Object>> lockOrderPayments(@Param("orderId") long orderId);

  int closePendingPayment(@Param("reason") String reason, @Param("paymentId") long paymentId);

  int acknowledgeCreateCommands(@Param("orderId") long orderId);

  int completeCreateProcesses(@Param("orderId") long orderId);

  List<Map<String,Object>> findPaymentNotification(
    @Param("channel") String channel,
    @Param("notificationId") String notificationId);

  List<Map<String,Object>> lockByChannelTransaction(@Param("channelTransactionNo") String channelTransactionNo);

  List<Map<String,Object>> findRefundNotification(
    @Param("channel") String channel,
    @Param("notificationId") String notificationId);

  List<Map<String,Object>> lockRefundForChannel(@Param("refundNumber") String refundNumber);

  List<Map<String,Object>> recoverableProcesses();

  int retryProcess(
    @Param("operator") String operator,
    @Param("reason") String reason,
    @Param("processId") String processId);

  int retryProcessCommands(@Param("processId") String processId);

  int markUnknownResults();

  List<Map<String,Object>> findResult(@Param("resultId") String resultId);

  List<Map<String,Object>> lockPaymentByNumber(@Param("paymentNumber") String paymentNumber);

  int recordProcessFailure(
    @Param("status") String status,
    @Param("error") String error,
    @Param("processId") String processId);

  int completeProcess(@Param("processId") String processId);

  int acknowledgeProcessCommands(@Param("processId") String processId);

  int markPaymentSucceeded(@Param("paymentId") long paymentId);

  int markPaymentTerminal(
    @Param("status") String status,
    @Param("reason") String reason,
    @Param("paymentId") long paymentId);

  List<Map<String,Object>> lockRefundByNumber(@Param("refundNumber") String refundNumber);

  int applyRefundResult(
    @Param("status") String status,
    @Param("channelRefundNo") String channelRefundNo,
    @Param("reason") String reason,
    @Param("succeeded") boolean succeeded,
    @Param("failedTimestamp") boolean failedTimestamp,
    @Param("refundId") long refundId);

  int recordRefundedAmount(@Param("amount") Object amount, @Param("paymentId") long paymentId);

  int insertResult(
    @Param("resultId") String resultId,
    @Param("processId") String processId,
    @Param("orderId") long orderId,
    @Param("resultType") String resultType,
    @Param("outcome") String outcome,
    @Param("fingerprint") String fingerprint,
    @Param("payload") String payload,
    @Param("status") String status,
    @Param("error") String error,
    @Param("correlationId") String correlationId,
    @Param("causationId") String causationId,
    @Param("traceparent") String traceparent);

  int insertPaymentNotification(
    @Param("paymentId") long paymentId,
    @Param("channel") String channel,
    @Param("notificationId") String notificationId,
    @Param("channelTransactionNo") String channelTransactionNo,
    @Param("status") String status,
    @Param("digest") String digest,
    @Param("failure") String failure,
    @Param("channelStatus") String channelStatus);

  int insertRefundNotification(
    @Param("refundId") long refundId,
    @Param("channel") String channel,
    @Param("notificationId") String notificationId,
    @Param("channelRefundNo") String channelRefundNo,
    @Param("status") String status,
    @Param("digest") String digest,
    @Param("failure") String failure);

  int insertResultOutbox(
    @Param("eventId") String eventId,
    @Param("aggregateType") String aggregateType,
    @Param("aggregateId") String aggregateId,
    @Param("eventType") String eventType,
    @Param("topic") String topic,
    @Param("payload") String payload);

  int insertCommandInbox(
    @Param("id") String id,
    @Param("kind") String kind,
    @Param("fingerprint") String fingerprint);

  List<Map<String,Object>> findCommandInbox(@Param("id") String id);

  int finishCommandInbox(
    @Param("status") String status,
    @Param("response") String response,
    @Param("error") String error,
    @Param("id") String id,
    @Param("fingerprint") String fingerprint);

  List<Map<String,Object>> findMerchant(@Param("channel") String channel);

  List<Map<String,Object>> findByIdempotency(@Param("key") String key, @Param("lock") boolean lock);

  List<String> lockProcessIds(@Param("orderId") long orderId);

  int insertPaymentEvent(
    @Param("aggregateId") long aggregateId,
    @Param("eventId") String eventId,
    @Param("type") String type,
    @Param("fromStatus") String fromStatus,
    @Param("toStatus") String toStatus,
    @Param("detail") String detail);

  int insertRefundEvent(
    @Param("aggregateId") long aggregateId,
    @Param("eventId") String eventId,
    @Param("type") String type,
    @Param("fromStatus") String fromStatus,
    @Param("toStatus") String toStatus,
    @Param("detail") String detail);
}
