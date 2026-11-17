package vip.fairy.commerceflow.commerce.payment.application.contract.v1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RefundSnapshotV1(
  int schemaVersion,
  String refundNumber,
  Long orderId,
  String status,
  String inventoryStatus,
  BigDecimal amount,
  String currency,
  String reasonCode,
  String reasonDetail,
  String channelRefundNo,
  String failureReason,
  Instant requestedAt,
  Instant succeededAt,
  Instant failedAt,
  List<Event> events
) {

  public static final int VERSION = 1;

  public record Event(
    Long id,
    String eventType,
    String fromStatus,
    String toStatus,
    String detail,
    Instant createdAt
  ) {
  }
}
