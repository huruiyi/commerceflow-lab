package com.tanerinal.education.web.dto;

import com.tanerinal.education.domain.CustomerOrder;

import java.math.BigDecimal;
import java.time.Instant;

public record OrderResponse(Long id, Instant createdAt, String productName, BigDecimal price,
                            int quantity, BigDecimal totalPrice, String thumbnailUrl, String note) {
  public static OrderResponse from(CustomerOrder order) {
    return new OrderResponse(order.getId(), order.getCreatedAt(), order.getProduct().getName(),
        order.getProduct().getPrice(), order.getQuantity(),
        order.getProduct().getPrice().multiply(BigDecimal.valueOf(order.getQuantity())),
        order.getProduct().getThumbnailUrl(), order.getNote());
  }
}
