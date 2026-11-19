package vip.fairy.commerceflow.commerce.cart.application.contract.v1;

import java.math.BigDecimal;
import java.util.List;

public interface RejectedCheckoutCartV1 {

  void restoreMissing(Long customerId, List<Item> items);

  record Item(Long productId, Long merchantId, String productName, String thumbnailUrl, BigDecimal unitPrice,
              int quantity) {
  }
}
