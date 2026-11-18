package vip.fairy.commerceflow.inventory.api.internal;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Document;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryOutboundV1.Receipt;
import vip.fairy.commerceflow.inventory.application.service.DeliveredInventoryReconciliationService;
import vip.fairy.commerceflow.inventory.application.service.InventoryOutboundService;
import vip.fairy.commerceflow.inventory.infrastructure.transport.InventoryTrafficGate;

@RestController
@RequestMapping("/internal/v1/inventory/outbound-documents")
public class InventoryOutboundHttpAdapter {

  private final InventoryTrafficGate traffic;
  private final InventoryOutboundService outbound;
  private final ObjectMapper mapper;

  public InventoryOutboundHttpAdapter(InventoryTrafficGate traffic, InventoryOutboundService outbound,
                                     ObjectMapper mapper) {
    this.traffic = traffic;
    this.outbound = outbound;
    this.mapper = mapper;
  }

  @PostMapping
  Receipt post(@RequestBody String payload, @RequestHeader("X-CommerceFlow-Content-SHA256") String contentHash) {
    traffic.requireOpen();
    if (!DeliveredInventoryReconciliationService.hash(payload).equals(contentHash)) {
      throw new IllegalArgumentException("Inventory outbound payload hash is invalid");
    }
    try {
      return outbound.post(mapper.readValue(payload, Document.class));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory outbound payload is invalid", exception);
    }
  }
}
