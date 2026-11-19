package vip.fairy.commerceflow.commerce.inventory.application;

import java.util.List;

import vip.fairy.commerceflow.commerce.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.commerce.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.transport.MerchantInventoryRemoteClient;
import org.springframework.stereotype.Service;

@Service
public class MerchantInventoryService {

  private final MerchantInventoryRemoteClient inventory;

  public MerchantInventoryService(MerchantInventoryRemoteClient inventory) {
    this.inventory = inventory;
  }

  public List<MerchantInventoryItem> list(Long merchantId) {
    return inventory.list(merchantId);
  }

  public List<InventoryMovementView> movements(Long merchantId, Long productId) {
    return inventory.movements(merchantId, productId);
  }

  public MerchantInventoryItem adjust(Long merchantId, Long productId, int delta, String reason, Long warehouseId) {
    return inventory.adjust(merchantId, productId, delta, reason, warehouseId);
  }
}
