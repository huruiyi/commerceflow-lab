package vip.fairy.commerceflow.inventory.api.internal;

import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.service.DeliveredInventoryReconciliationService;
import vip.fairy.commerceflow.inventory.application.service.InventoryLineCreditService;
import vip.fairy.commerceflow.inventory.application.service.InventoryLineCreditService.Credit;
import vip.fairy.commerceflow.inventory.application.service.InventoryLineCreditService.Origin;
import vip.fairy.commerceflow.inventory.application.service.InventoryLineCreditService.Source;
import vip.fairy.commerceflow.inventory.infrastructure.transport.InventoryTrafficGate;

@RestController
public class InventoryLineCreditHttpAdapter {

  private final InventoryTrafficGate traffic;
  private final InventoryLineCreditService credits;
  private final ObjectMapper mapper;

  public InventoryLineCreditHttpAdapter(InventoryTrafficGate traffic, InventoryLineCreditService credits,
                                        ObjectMapper mapper) {
    this.traffic = traffic;
    this.credits = credits;
    this.mapper = mapper;
  }

  @PostMapping("/internal/v1/inventory/line-credits")
  boolean credit(@RequestBody String payload, @RequestHeader("X-CommerceFlow-Content-SHA256") String hash) {
    verify(payload, hash);
    var request = mapper.readValue(payload, Request.class);
    return credits.credit(request.credit(), request.apply());
  }

  @PostMapping("/internal/v1/inventory/line-credits/sources")
  List<Source> sources(@RequestBody String payload, @RequestHeader("X-CommerceFlow-Content-SHA256") String hash) {
    verify(payload, hash);
    return credits.sources(mapper.readValue(payload, Origin.class));
  }

  @PostMapping("/internal/v1/inventory/line-credits/exchange")
  boolean exchange(@RequestBody String payload, @RequestHeader("X-CommerceFlow-Content-SHA256") String hash) {
    verify(payload, hash);
    var request = mapper.readValue(payload, ExchangeRequest.class);
    return switch (request.action()) {
      case RESERVE -> credits.reserveReplacement(request.credit());
      case SHIP -> {
        credits.shipReplacement(request.credit());
        yield true;
      }
    };
  }

  private void verify(String payload, String hash) {
    traffic.requireOpen();
    if (!DeliveredInventoryReconciliationService.hash(payload).equals(hash)) {
      throw new IllegalArgumentException("Inventory line credit payload hash is invalid");
    }
  }

  record Request(Credit credit, boolean apply) {
  }

  record ExchangeRequest(Credit credit, ExchangeAction action) {
  }

  enum ExchangeAction {
    RESERVE, SHIP
  }
}
