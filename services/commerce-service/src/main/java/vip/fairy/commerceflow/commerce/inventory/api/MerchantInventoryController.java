package vip.fairy.commerceflow.commerce.inventory.api;

import jakarta.validation.Valid;

import java.util.List;

import vip.fairy.commerceflow.commerce.inventory.domain.InventoryMovementView;
import vip.fairy.commerceflow.commerce.inventory.domain.MerchantInventoryItem;
import vip.fairy.commerceflow.commerce.identity.application.CurrentUserService;
import vip.fairy.commerceflow.commerce.inventory.application.MerchantInventoryService;
import vip.fairy.commerceflow.commerce.inventory.api.InventoryAdjustmentRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/merchant/inventory")
@PreAuthorize("hasRole('MERCHANT')")
public class MerchantInventoryController {

  private final CurrentUserService currentUsers;
  private final MerchantInventoryService inventory;

  public MerchantInventoryController(CurrentUserService currentUsers,
                                     MerchantInventoryService inventory) {
    this.currentUsers = currentUsers;
    this.inventory = inventory;
  }

  @GetMapping
  List<MerchantInventoryItem> list(Authentication authentication) {
    return inventory.list(currentUsers.require(authentication));
  }

  @GetMapping("/{productId}/movements")
  List<InventoryMovementView> movements(@PathVariable Long productId,
                                        Authentication authentication) {
    return inventory.movements(currentUsers.require(authentication), productId);
  }

  @PostMapping("/{productId}/adjustments")
  MerchantInventoryItem adjust(@PathVariable Long productId,
                               @Valid @RequestBody InventoryAdjustmentRequest request, Authentication authentication) {
    return inventory.adjust(currentUsers.require(authentication), productId,
      request.delta(), request.reason());
  }
}
