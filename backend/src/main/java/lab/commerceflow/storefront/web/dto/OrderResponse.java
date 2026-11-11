package lab.commerceflow.storefront.web.dto;

import lab.commerceflow.storefront.domain.CustomerOrder;
import lab.commerceflow.storefront.domain.OrderView;
import lab.commerceflow.storefront.domain.Product;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, Long productId, Instant createdAt, String productName, BigDecimal price,
                            int quantity, BigDecimal totalPrice, String thumbnailUrl, String note) {
  public static OrderResponse from(CustomerOrder order, Product product) {
    return new OrderResponse(order.getId(), order.getProductId(), order.getCreatedAt(), product.getName(),
        product.getPrice(), order.getQuantity(),
        product.getPrice().multiply(BigDecimal.valueOf(order.getQuantity())),
        product.getThumbnailUrl(), order.getNote());
  }

  public static OrderResponse from(OrderView order) {
    return new OrderResponse(order.getId(), order.getProductId(), order.getCreatedAt(), order.getProductName(),
        order.getPrice(), order.getQuantity(),
        order.getPrice().multiply(BigDecimal.valueOf(order.getQuantity())),
        order.getThumbnailUrl(), order.getNote());
  }
}
