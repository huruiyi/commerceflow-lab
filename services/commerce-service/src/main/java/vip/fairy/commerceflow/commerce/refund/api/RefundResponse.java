package vip.fairy.commerceflow.commerce.refund.api;

import vip.fairy.commerceflow.commerce.refund.domain.RefundEventView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundView;
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
  public static RefundResponse from(RefundView value, List<RefundEventView> events) {
    return new RefundResponse(
      value.getRefundNumber(),
      value.getOrderId(),
      value.getStatus(),
      value.getInventoryStatus(),
      value.getAmount(),
      value.getCurrency(),
      value.getReasonCode(),
      value.getReasonDetail(),
      value.getChannelRefundNo(),
      value.getFailureReason(),
      value.getRequestedAt(),
      value.getSucceededAt(),
      value.getFailedAt(),
      events.stream().map(Event::from).toList()
    );
  }

  public static RefundResponse from(RefundSnapshotV1 value) {
    return new RefundResponse(value.refundNumber(), value.orderId(), value.status(),
      value.inventoryStatus(), value.amount(), value.currency(), value.reasonCode(),
      value.reasonDetail(), value.channelRefundNo(), value.failureReason(), value.requestedAt(),
      value.succeededAt(), value.failedAt(), value.events().stream()
        .map(Event::from)
        .toList());
  }

  public record Event(Long id, String eventType, String fromStatus, String toStatus, String detail, Instant createdAt) {
    public static Event from(RefundEventView value) {
      return new Event(
        value.getId(),
        value.getEventType(),
        value.getFromStatus(),
        value.getToStatus(),
        value.getDetail(),
        value.getCreatedAt()
      );
    }

    public static Event from(RefundSnapshotV1.Event value) {
      return new Event(value.id(), value.eventType(), value.fromStatus(), value.toStatus(),
        value.detail(), value.createdAt());
    }
  }
}
