package vip.fairy.commerceflow.commerce.cart.api;

import vip.fairy.commerceflow.commerce.cart.domain.OrderView;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, Long productId, Instant createdAt, String productName, BigDecimal price,
                            int quantity, BigDecimal totalPrice, String thumbnailUrl, String note) {
  public static OrderResponse from(OrderView order) {
    return new OrderResponse(order.getId(), order.getProductId(), order.getCreatedAt(), order.getProductName(),
      order.getPrice(), order.getQuantity(),
      order.getPrice().multiply(BigDecimal.valueOf(order.getQuantity())),
      order.getThumbnailUrl(), order.getNote());
  }
}
