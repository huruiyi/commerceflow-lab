package vip.fairy.commerceflow.inventory.application.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vip.fairy.commerceflow.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.inventory.domain.ProductInventory;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.InventoryMapper;
import vip.fairy.commerceflow.inventory.infrastructure.persistence.MerchantInventoryMapper;

@Service
public class MerchantInventoryService {

  private final MerchantInventoryMapper merchantInventory;
  private final InventoryMapper inventory;

  public MerchantInventoryService(MerchantInventoryMapper merchantInventory,
                                  InventoryMapper inventory) {
    this.merchantInventory = merchantInventory;
    this.inventory = inventory;
  }

  public List<MerchantInventoryItem> list(Long merchantId) {
    return merchantInventory.list(merchantId);
  }

  public List<InventoryMovementView> movements(Long merchantId, Long productId) {
    requireProduct(merchantId, productId);
    return merchantInventory.movements(merchantId, productId);
  }

  @Transactional
  public MerchantInventoryItem adjust(Long merchantId, Long productId, int delta, String reason) {
    if (delta == 0) {
      throw new InventoryBusinessRuleException("Inventory adjustment cannot be zero");
    }
    requireProduct(merchantId, productId);
    if (merchantInventory.adjust(merchantId, productId, delta) != 1) {
      throw new InventoryBusinessRuleException(
        "Inventory adjustment would make available stock negative");
    }
    ProductInventory balance = inventory.inventory(productId);
    if (balance == null) {
      throw new InventoryNotFoundException("Product inventory not found");
    }
    inventory.insertMovement("adjust:" + UUID.randomUUID(), null, productId,
      delta > 0 ? "MANUAL_INCREASE" : "MANUAL_DECREASE", Math.abs(delta), balance,
      reason.trim());
    return requireProduct(merchantId, productId);
  }

  private MerchantInventoryItem requireProduct(Long merchantId, Long productId) {
    MerchantInventoryItem item = merchantInventory.find(merchantId, productId);
    if (item == null) {
      throw new InventoryNotFoundException("Product inventory not found");
    }
    return item;
  }
}
