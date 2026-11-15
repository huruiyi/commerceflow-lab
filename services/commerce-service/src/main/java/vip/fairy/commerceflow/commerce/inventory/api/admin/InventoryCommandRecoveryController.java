package vip.fairy.commerceflow.commerce.inventory.api.admin;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.commerce.inventory.application.InventoryCommandRecoveryService;
import vip.fairy.commerceflow.commerce.inventory.application.InventoryCommandRecoveryService.RecoveryView;

@RestController
@RequestMapping("/api/admin/inventory-command-recoveries")
@PreAuthorize("hasRole('ADMIN')")
public class InventoryCommandRecoveryController {

  private final InventoryCommandRecoveryService recoveries;

  public InventoryCommandRecoveryController(InventoryCommandRecoveryService recoveries) {
    this.recoveries = recoveries;
  }

  @GetMapping
  List<RecoveryView> list(@RequestParam(defaultValue = "100") int limit) {
    return recoveries.reconciliationRequired(limit);
  }

  @PostMapping("/{commandId}/retry")
  RecoveryView retry(@PathVariable String commandId,
                     @Valid @RequestBody RetryRequest request,
                     Authentication authentication) {
    return recoveries.retry(commandId, authentication.getName(), request.reason());
  }

  public record RetryRequest(@NotBlank String reason) {
  }
}
