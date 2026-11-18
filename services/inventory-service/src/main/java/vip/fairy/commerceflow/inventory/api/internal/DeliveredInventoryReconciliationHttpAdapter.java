package vip.fairy.commerceflow.inventory.api.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.contract.v1.DeliveredInventoryEvidenceV1;
import vip.fairy.commerceflow.inventory.application.service.DeliveredInventoryReconciliationService;
import vip.fairy.commerceflow.inventory.application.service.DeliveredInventoryReconciliationService.Receipt;
import vip.fairy.commerceflow.inventory.infrastructure.transport.InventoryTrafficGate;

@RestController
@RequestMapping("/internal/v1/inventory/delivered-reconciliations")
public class DeliveredInventoryReconciliationHttpAdapter {

  private final InventoryTrafficGate traffic;
  private final DeliveredInventoryReconciliationService reconciliation;
  private final ObjectMapper mapper;

  public DeliveredInventoryReconciliationHttpAdapter(InventoryTrafficGate traffic,
                                                       DeliveredInventoryReconciliationService reconciliation,
                                                       ObjectMapper mapper) {
    this.traffic = traffic;
    this.reconciliation = reconciliation;
    this.mapper = mapper;
  }

  @PostMapping
  Receipt reconcile(@RequestBody String payload,
                    @RequestHeader("X-CommerceFlow-Content-SHA256") String contentHash) {
    traffic.requireOpen();
    if (!DeliveredInventoryReconciliationService.hash(payload).equals(contentHash)) {
      throw new IllegalArgumentException("Inventory evidence payload hash is invalid");
    }
    try {
      return reconciliation.reconcile(mapper.readValue(payload, DeliveredInventoryEvidenceV1.class));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory evidence payload is invalid", exception);
    }
  }
}
