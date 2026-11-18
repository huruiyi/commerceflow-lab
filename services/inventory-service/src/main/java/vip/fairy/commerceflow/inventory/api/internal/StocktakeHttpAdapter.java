package vip.fairy.commerceflow.inventory.api.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.inventory.application.contract.v1.StocktakeV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.StocktakeV1.Baseline;
import vip.fairy.commerceflow.inventory.application.contract.v1.StocktakeV1.Receipt;
import vip.fairy.commerceflow.inventory.application.service.DeliveredInventoryReconciliationService;
import vip.fairy.commerceflow.inventory.application.service.StocktakeService;
import vip.fairy.commerceflow.inventory.infrastructure.transport.InventoryTrafficGate;

@RestController
@RequestMapping("/internal/v1/inventory/stocktakes")
public class StocktakeHttpAdapter {

  private final StocktakeService stocktakes;
  private final InventoryTrafficGate traffic;
  private final ObjectMapper json;

  public StocktakeHttpAdapter(StocktakeService stocktakes, InventoryTrafficGate traffic, ObjectMapper json) {
    this.stocktakes = stocktakes;
    this.traffic = traffic;
    this.json = json;
  }

  @GetMapping("/baseline/{productId}")
  Baseline baseline(@PathVariable long productId) {
    traffic.requireOpen();
    return stocktakes.baseline(productId);
  }

  @PostMapping
  Receipt apply(@RequestBody String payload, @RequestHeader("X-CommerceFlow-Content-SHA256") String contentHash) {
    traffic.requireOpen();
    if (!DeliveredInventoryReconciliationService.hash(payload).equals(contentHash)) {
      throw new IllegalArgumentException("Stocktake payload hash is invalid");
    }
    try {
      return stocktakes.apply(json.readValue(payload, StocktakeV1.class));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Stocktake payload is invalid", exception);
    }
  }
}
