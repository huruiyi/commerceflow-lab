package vip.fairy.commerceflow.commerce.inventory.domain;

import java.math.BigDecimal;

public record MerchantInventoryItem(
  Long productId,
  String productName,
  String thumbnailUrl,
  BigDecimal price,
  int stockAvailable,
  int stockReserved,
  int stockSold
) {
}
