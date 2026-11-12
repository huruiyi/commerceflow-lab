package vip.fairy.commerceflow.commerce.ordering.api;

import vip.fairy.commerceflow.commerce.logistics.api.LogisticsResponse;
import vip.fairy.commerceflow.commerce.refund.api.RefundResponse;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderAddressView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderItemView;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentView;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ShopOrderResponse(Long id, String orderNumber, String status, BigDecimal totalAmount,
                                String currency, Instant createdAt, Instant paymentDueAt, Instant cancelledAt,
                                String cancelReason,
                                Instant receivedAt, Instant completedAt, Instant logisticsDeliveredAt,
                                List<String> availableActions, List<Item> items, Address address, Payment payment,
                                LogisticsResponse logistics, RefundResponse refund) {
  public record Item(Long id, Long productId, String productName, String thumbnailUrl, BigDecimal unitPrice,
                     int quantity, BigDecimal subtotal) {
    public static Item from(OrderItemView i) {
      return new Item(i.getId(), i.getProductId(), i.getProductName(), i.getThumbnailUrl(), i.getUnitPrice(), i.getQuantity(), i.getSubtotal());
    }
  }

  public record Address(String recipientName, String phone, String countryCode, String stateProvince, String city,
                        String district, String addressLine1, String addressLine2, String postalCode, String label) {
    public static Address from(OrderAddressView a) {
      return a == null ? null : new Address(a.getRecipientName(), a.getPhone(), a.getCountryCode(), a.getStateProvince(), a.getCity(), a.getDistrict(), a.getAddressLine1(), a.getAddressLine2(), a.getPostalCode(), a.getLabel());
    }
  }

  public record Payment(String paymentNumber, String merchantNo, String channel, String channelTransactionNo,
                        String status, String failureReason, BigDecimal amount, BigDecimal refundedAmount,
                        String currency, Instant createdAt, Instant paidAt, Instant expiresAt, String checkoutUrl,
                        String actionType, String qrPayload) {
    public static Payment from(PaymentView p) {
      return p == null ? null : new Payment(p.getPaymentNumber(), p.getMerchantNo(), p.getChannel(), p.getChannelTransactionNo(), p.getStatus(), p.getFailureReason(), p.getAmount(), p.getRefundedAmount(), p.getCurrency(), p.getCreatedAt(), p.getPaidAt(), p.getExpiresAt(), p.getCheckoutUrl(), p.getActionType(), p.getQrPayload());
    }
  }
}
