package vip.fairy.commerceflow.commerce.inventory.application;

import vip.fairy.commerceflow.commerce.catalog.domain.Product;
import vip.fairy.commerceflow.commerce.inventory.domain.ProductInventory;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;

import java.util.List;
import java.util.UUID;

import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.commerce.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.InventoryMapper;
import vip.fairy.commerceflow.commerce.inventory.infrastructure.MerchantInventoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantInventoryService {
  private final MerchantInventoryMapper merchantInventory;
  private final InventoryMapper inventory;

  public MerchantInventoryService(MerchantInventoryMapper merchantInventory,
                                  InventoryMapper inventory) {
    this.merchantInventory = merchantInventory;
    this.inventory = inventory;
  }

  public List<MerchantInventoryItem> list(AppUser merchant) {
    return merchantInventory.list(merchant.getId());
  }

  public List<InventoryMovementView> movements(AppUser merchant, Long productId) {
    requireProduct(merchant, productId);
    return merchantInventory.movements(merchant.getId(), productId);
  }

  @Transactional
  public MerchantInventoryItem adjust(AppUser merchant, Long productId, int delta, String reason) {
    if (delta == 0) throw new BusinessRuleException("Inventory adjustment cannot be zero");
    requireProduct(merchant, productId);
    if (merchantInventory.adjust(merchant.getId(), productId, delta) != 1) {
      throw new BusinessRuleException("Inventory adjustment would make available stock negative");
    }
    ProductInventory balance = inventory.inventory(productId);
    inventory.insertMovement("adjust:" + UUID.randomUUID(), null, productId,
      delta > 0 ? "MANUAL_INCREASE" : "MANUAL_DECREASE", Math.abs(delta), balance,
      reason.trim());
    return requireProduct(merchant, productId);
  }

  private MerchantInventoryItem requireProduct(AppUser merchant, Long productId) {
    MerchantInventoryItem item = merchantInventory.find(merchant.getId(), productId);
    if (item == null) throw new ResourceNotFoundException("Product inventory not found");
    return item;
  }
}
