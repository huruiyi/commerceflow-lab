package vip.fairy.commerceflow.commerce.fulfillment.api;

import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.refund.api.RefundResponse;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MerchantOrderResponse(
  Long id,
  String orderNumber,
  String status,
  BigDecimal totalAmount,
  String currency,
  Instant createdAt,
  Instant confirmedAt,
  Instant shippedAt,
  String carrier,
  String trackingNumber,
  List<ShopOrderResponse.Item> items,
  ShopOrderResponse.Address address,
  List<Event> events,
  RefundResponse refund) {
  public record Event(Long id, Long actorId, String eventType, String fromStatus, String toStatus, String detail,
                      String triggerSource, Long scheduledTaskId, Instant createdAt) {
    public static Event from(OrderEventView value) {
      return new Event(value.getId(), value.getActorId(), value.getEventType(), value.getFromStatus(),
        value.getToStatus(), value.getDetail(), value.getTriggerSource(), value.getScheduledTaskId(),
        value.getCreatedAt());
    }
  }
}
