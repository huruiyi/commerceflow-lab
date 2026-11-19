package vip.fairy.commerceflow.commerce.ordering.api;

import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentSnapshotV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.RefundSnapshotV1;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ShopOrderResponse(
  Long id,
  String orderNumber,
  String status,
  String inventoryStatus,
  BigDecimal totalAmount,
  BigDecimal productSubtotal,
  BigDecimal shippingFee,
  BigDecimal discountAmount,
  String couponId,
  String currency,
  Instant createdAt,
  Instant paymentDueAt,
  Instant cancelledAt,
  String cancelReason,
  Instant receivedAt,
  Instant completedAt,
  Instant logisticsDeliveredAt,
  List<String> availableActions,
  List<Item> items,
  Address address,
  Payment payment,
  LogisticsResponse logistics,
  RefundSnapshotV1 refund) {
  public record Item(
    Long id,
    Long productId,
    String productName,
    String thumbnailUrl,
    BigDecimal unitPrice,
    int quantity,
    BigDecimal subtotal,
    BigDecimal discountAmount) {
    public static Item from(OrderItemView item) {
      return new Item(
        item.getId(),
        item.getProductId(),
        item.getProductName(),
        item.getThumbnailUrl(),
        item.getUnitPrice(),
        item.getQuantity(),
        item.getSubtotal(),
        item.getDiscountAmount());
    }
  }

  public record Address(
    String recipientName,
    String phone,
    String countryCode,
    String stateProvince,
    String city,
    String district,
    String addressLine1,
    String addressLine2,
    String postalCode,
    String label) {
    public static Address from(OrderAddressView address) {
      return address == null
        ? null
        : new Address(
          address.getRecipientName(),
          address.getPhone(),
          address.getCountryCode(),
          address.getStateProvince(),
          address.getCity(),
          address.getDistrict(),
          address.getAddressLine1(),
          address.getAddressLine2(),
          address.getPostalCode(),
          address.getLabel());
    }
  }

  public record Payment(
    String paymentNumber,
    String merchantNo,
    String channel,
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
    String qrPayload) {
    public static Payment from(PaymentSnapshotV1 payment) {
      return payment == null
        ? null
        : new Payment(
          payment.paymentNumber(),
          payment.merchantNo(),
          payment.channel().name(),
          payment.channelTransactionNo(),
          payment.status(),
          payment.failureReason(),
          payment.amount(),
          payment.refundedAmount(),
          payment.currency(),
          payment.createdAt(),
          payment.paidAt(),
          payment.expiresAt(),
          payment.checkoutUrl(),
          payment.actionType(),
          payment.qrPayload());
    }
  }
}
