package lab.commerceflow.storefront.web.dto;

import lab.commerceflow.storefront.domain.OrderEventView;
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
  public record Event(Long id,String eventType,String fromStatus,String toStatus,String detail,Instant createdAt) {
    public static Event from(OrderEventView value) {
      return new Event(value.getId(),value.getEventType(),value.getFromStatus(),value.getToStatus(),value.getDetail(),value.getCreatedAt());
    }
  }
}
