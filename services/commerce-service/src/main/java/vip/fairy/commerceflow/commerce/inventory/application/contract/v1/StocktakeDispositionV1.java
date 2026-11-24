package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.time.Instant;

/** Read-only proof for an approved stocktake applied to an order and product. */
public interface StocktakeDispositionV1 {

  boolean applied(String reference, long orderId, long productId, Instant reviewedAt);
}
