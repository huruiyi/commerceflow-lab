package vip.fairy.commerceflow.commerce.fulfillment.api;

import vip.fairy.commerceflow.commerce.ordering.api.ShopOrderResponse;
import vip.fairy.commerceflow.commerce.logistics.application.contract.LogisticsPackageQueryV1.PackageTracking;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderEventView;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record MerchantOrderResponse(
  Long id,
  String orderNumber,
  String status,
  String inventoryStatus,
  BigDecimal totalAmount,
  String currency,
  Instant createdAt,
  Instant confirmedAt,
  Instant shippedAt,
  String carrier,
  String trackingNumber,
  long shipmentVersion,
  List<ShopOrderResponse.Item> items,
  ShopOrderResponse.Address address,
  List<Event> events,
  List<PackageTracking> packages,
  RefundSnapshotV1 refund) {
  public record Event(
    Long id,
    Long actorId,
    String eventType,
    String fromStatus,
    String toStatus,
    String detail,
    String triggerSource,
    Long scheduledTaskId,
    Instant createdAt) {
    public static Event from(OrderEventView value) {
      return new Event(value.getId(), value.getActorId(), value.getEventType(), value.getFromStatus(),
        value.getToStatus(), value.getDetail(), value.getTriggerSource(), value.getScheduledTaskId(),
        value.getCreatedAt());
    }
  }
}
