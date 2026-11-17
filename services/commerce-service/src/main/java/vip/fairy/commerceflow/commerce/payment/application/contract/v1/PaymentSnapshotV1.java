package vip.fairy.commerceflow.commerce.payment.application.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentSnapshotV1(
  int schemaVersion,
  Long orderId,
  String paymentNumber,
  String merchantNo,
  PaymentChannelV1 channel,
  String channelTransactionNo,
  String status,
  String failureReason,
  BigDecimal amount,
  BigDecimal refundedAmount,
  String currency,
  Instant createdAt,
  Instant paidAt,
  Instant expiresAt,
  String checkoutUrl,
  String actionType,
  String qrPayload
) {

  public static final int VERSION = 1;
}
