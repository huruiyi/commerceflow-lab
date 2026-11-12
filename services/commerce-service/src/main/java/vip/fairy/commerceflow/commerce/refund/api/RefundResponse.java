package vip.fairy.commerceflow.commerce.refund.api;

import vip.fairy.commerceflow.commerce.refund.domain.RefundEventView;
import vip.fairy.commerceflow.commerce.refund.domain.RefundView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RefundResponse(String refundNumber, Long orderId, String status, BigDecimal amount, String currency,
                             String reasonCode, String reasonDetail, String channelRefundNo, String failureReason,
                             Instant requestedAt,
                             Instant succeededAt, Instant failedAt, List<Event> events) {
  public static RefundResponse from(RefundView value, List<RefundEventView> events) {
    return new RefundResponse(
      value.getRefundNumber(), value.getOrderId(), value.getStatus(), value.getAmount(), value.getCurrency(), value.getReasonCode(),
      value.getReasonDetail(), value.getChannelRefundNo(), value.getFailureReason(), value.getRequestedAt(), value.getSucceededAt(),
      value.getFailedAt(), events.stream().map(Event::from).toList());
  }

  public record Event(Long id, String eventType, String fromStatus, String toStatus, String detail, Instant createdAt) {
    public static Event from(RefundEventView value) {
      return new Event(value.getId(), value.getEventType(), value.getFromStatus(), value.getToStatus(), value.getDetail(), value.getCreatedAt());
    }
  }
}
