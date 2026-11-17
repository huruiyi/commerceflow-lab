package vip.fairy.commerceflow.commerce.ordering.domain;

import vip.fairy.commerceflow.commerce.cart.domain.CheckoutCartItem;

import java.math.BigDecimal;

public record OrderItemSnapshot(
  /** Stable product identity captured at checkout. */
  Long productId,
  /** Stable merchant identity captured at checkout. */
  Long merchantId,
  /** Product display name captured at checkout. */
  String productName,
  /** Product thumbnail URL captured at checkout. */
  String thumbnailUrl,
  /** Unit price in the order currency. */
  BigDecimal unitPrice,
  /** Positive quantity purchased. */
  int quantity
) {

  public static OrderItemSnapshot from(CheckoutCartItem item) {
    return new OrderItemSnapshot(item.getProductId(), item.getMerchantId(), item.getProductName(),
      item.getThumbnailUrl(), item.getUnitPrice(), item.getQuantity());
  }

  public BigDecimal subtotal() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
  }
}
