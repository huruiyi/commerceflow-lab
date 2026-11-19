package vip.fairy.commerceflow.commerce.cart.application.contract.v1;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface RepurchaseCartV1 {

  Result addOrderItems(Long customerId, Long orderId, UUID requestId, List<Item> items);

  record Item(Long productId, String originalName, int quantity) {
  }

  record Result(List<AddedLine> added, List<SkippedLine> skipped) {
  }

  record AddedLine(Long productId, String productName, int addedQuantity, int cartQuantity, BigDecimal unitPrice) {
  }

  record SkippedLine(Long productId, String productName, int requestedQuantity, Reason reason,
                     Integer additionalAvailable) {
  }

  enum Reason {
    UNLISTED,
    SOLD_OUT,
    INSUFFICIENT_STOCK,
    CART_LIMIT
  }
}
