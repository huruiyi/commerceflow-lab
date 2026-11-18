package vip.fairy.commerceflow.commerce.inventory.api.admin;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import vip.fairy.commerceflow.commerce.inventory.application.InventoryRemoteRehearsalService;
import vip.fairy.commerceflow.commerce.inventory.application.DeliveredInventoryRecoveryService;
import vip.fairy.commerceflow.commerce.inventory.application.DeliveredInventoryRecoveryService.Evidence;
import vip.fairy.commerceflow.commerce.inventory.application.DeliveredInventoryRecoveryService.EvidenceContext;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.DeliveredInventoryEvidenceV1.Receipt;
import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandResultV1;

@RestController
@RequestMapping("/api/admin/inventory-command-recoveries")
@PreAuthorize("hasRole('ADMIN')")
public class InventoryCommandRecoveryController {

  private final InventoryCommandRecoveryService recoveries;
  private final InventoryRemoteRehearsalService rehearsals;
  private final DeliveredInventoryRecoveryService delivered;

  public InventoryCommandRecoveryController(InventoryCommandRecoveryService recoveries,
                                            InventoryRemoteRehearsalService rehearsals,
                                            DeliveredInventoryRecoveryService delivered) {
    this.recoveries = recoveries;
    this.rehearsals = rehearsals;
    this.delivered = delivered;
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

  @PostMapping("/{commandId}/remote-rehearsal")
  InventoryCommandResultV1 rehearse(@PathVariable String commandId) {
    return rehearsals.replayAcknowledged(commandId);
  }

  @PostMapping("/{commandId}/warehouse-evidence")
  Receipt reconcileDelivered(@PathVariable String commandId, @Valid @RequestBody EvidenceRequest request,
                             Authentication authentication) {
    return delivered.reconcile(commandId, authentication.getName(), request.lines());
  }

  @GetMapping("/{commandId}/warehouse-evidence")
  EvidenceContext warehouseEvidenceContext(@PathVariable String commandId) {
    return delivered.context(commandId);
  }

  public record RetryRequest(@NotBlank String reason) {
  }

  public record EvidenceRequest(@NotEmpty List<Evidence> lines) {
  }
}
