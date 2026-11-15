package vip.fairy.commerceflow.commerce.inventory.application;

import vip.fairy.commerceflow.commerce.inventory.infrastructure.MerchantInventoryMapper;
import vip.fairy.commerceflow.commerce.shared.application.CatalogSnapshotChanged;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CatalogSnapshotProjection {

  private final MerchantInventoryMapper inventory;

  public CatalogSnapshotProjection(MerchantInventoryMapper inventory) {
    this.inventory = inventory;
  }

  @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
  public void update(CatalogSnapshotChanged event) {
    int updated = inventory.updateCatalogSnapshot(
      event.productId(), event.merchantId(), event.productName(), event.thumbnailUrl(), event.unitPrice());
    if (updated != 1) {
      throw new IllegalStateException("Product inventory snapshot is missing");
    }
  }
}
