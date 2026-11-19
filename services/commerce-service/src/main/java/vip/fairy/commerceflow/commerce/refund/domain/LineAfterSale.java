package vip.fairy.commerceflow.commerce.refund.domain;

import java.math.BigDecimal;

public record LineAfterSale(String id, Long orderId, Long orderLineId, Long productId, Long customerId, Long merchantId,
            int quantity, BigDecimal merchandiseAmount, BigDecimal shippingAmount, String currency,
            String kind, String status, String reason, String decision, String trackingNumber,
            String refundNumber, BigDecimal returnShippingCost, Integer receivedQuantity, String lastError,
            Long warehouseId, String outboundId) {
  public BigDecimal total() {
    return merchandiseAmount.add(shippingAmount);
  }
}
