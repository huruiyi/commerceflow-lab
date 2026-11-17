package vip.fairy.commerceflow.commerce.refund.api;

import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RefundResponse(
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
  public static RefundResponse from(RefundSnapshotV1 value) {
    return new RefundResponse(value.refundNumber(), value.orderId(), value.status(),
      value.inventoryStatus(), value.amount(), value.currency(), value.reasonCode(),
      value.reasonDetail(), value.channelRefundNo(), value.failureReason(), value.requestedAt(),
      value.succeededAt(), value.failedAt(), value.events().stream()
        .map(Event::from)
        .toList());
  }

  public record Event(Long id, String eventType, String fromStatus, String toStatus, String detail, Instant createdAt) {
    public static Event from(RefundSnapshotV1.Event value) {
      return new Event(value.id(), value.eventType(), value.fromStatus(), value.toStatus(),
        value.detail(), value.createdAt());
    }
  }
}
