package vip.fairy.commerceflow.order.application.contract.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class OrderStoreV1 {

  private OrderStoreV1() {
  }

  public record CreateOrder(
    String orderNumber, Long customerId, BigDecimal totalAmount, String currency,
    Instant paymentDueAt, Long actorId, List<ItemSnapshot> items, AddressSnapshot address,
    BigDecimal shippingFee, BigDecimal discountAmount, String couponId
  ) {
  }

  public record ItemSnapshot(Long productId, Long merchantId, String productName,
                             String thumbnailUrl, BigDecimal unitPrice, int quantity,
                             BigDecimal subtotal, BigDecimal discountAmount) {
  }

  public record AddressSnapshot(String recipientName, String phone, String countryCode,
                                String stateProvince, String city, String district,
                                String addressLine1, String addressLine2, String postalCode,
                                String label) {
  }

  public record OrderView(Long id, String orderNumber, Long customerId, String status,
                          String inventoryStatus, BigDecimal totalAmount, String currency,
                          Instant createdAt, Instant updatedAt, Instant confirmedAt,
                          Instant shippedAt, String carrier, String trackingNumber,
                          Instant paymentDueAt, Instant cancelledAt, String cancelReason,
                          Instant receivedAt, Instant completedAt,
                          Instant logisticsDeliveredAt, long shipmentVersion,
                          BigDecimal productSubtotal, BigDecimal shippingFee, BigDecimal discountAmount,
                          String couponId) {
  }

  public record ItemView(Long id, Long productId, Long merchantId, String productName,
                         String thumbnailUrl, BigDecimal unitPrice, int quantity,
                         BigDecimal subtotal, BigDecimal discountAmount) {
  }

  public record AddressView(String recipientName, String phone, String countryCode,
                            String stateProvince, String city, String district,
                            String addressLine1, String addressLine2, String postalCode,
                            String label) {
  }

  public record EventView(Long id, Long actorId, String eventType, String fromStatus,
                          String toStatus, String detail, String triggerSource,
                          Long scheduledTaskId, Instant createdAt) {
  }

  public record ReviewableItem(Long orderItemId, Long productId) {
  }

  public record Query(QueryType type, Long customerId, Long merchantId, Long orderId,
                      Long itemId) {
  }

  public enum QueryType {
    STATUS, LIST_CUSTOMER, FIND_CUSTOMER, FIND_BY_ID, LIST_MERCHANT, FIND_MERCHANT,
    ITEMS, SHIPMENT_LINES, ADDRESS, EVENTS, ORDER_CUSTOMER, REVIEWABLE
  }

  public record QueryResult(boolean found, String status, Long customerId, OrderView order,
                            List<OrderView> orders, List<ItemView> items, AddressView address,
                            List<EventView> events, ReviewableItem reviewableItem) {
  }

  public record Mutation(String mutationId, MutationType type, Long orderId, Long actorId,
                         String fromStatus, String toStatus, String reason, String carrier,
                         String trackingNumber, String eventType, String detail,
                         String triggerSource, Long scheduledTaskId, Instant occurredAt,
                         @JsonInclude(JsonInclude.Include.NON_NULL) Long expectedShipmentVersion) {
  }

  public enum MutationType {
    MARK_LOGISTICS_DELIVERED, INSERT_EVENT, UPDATE_STATUS, UPDATE_INVENTORY_STATUS,
    START_INVENTORY_COMMIT,
    START_CANCELLATION, FINISH_CANCELLATION, RESTORE_PAID_CANCELLATION_RACE,
    RESTORE_PAID_LATE_SUCCESS, MARK_RECEIVED, MARK_COMPLETED, MERCHANT_CONFIRM,
    RESERVE_SHIPMENT, RESERVE_NEW_SHIPMENT, ABORT_SHIPMENT, MERCHANT_SHIP, UPDATE_SHIPMENT, START_REFUND
  }

  public record MutationResult(int changed, String previousStatus) {

    public MutationResult(int changed) {
      this(changed, null);
    }
  }
}
