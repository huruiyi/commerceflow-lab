package vip.fairy.commerceflow.commerce.payment.domain;

import java.time.Instant;

/** 支付或退款远程命令的可恢复进程状态。 */
public record PaymentProcessView(
  String processId,
  Long orderId,
  String status,
  String commandId,
  String commandFingerprint,
  String correlationId,
  Instant resultDeadlineAt,
  String recoveredBy,
  String recoveryReason,
  Instant recoveredAt
) {
}
