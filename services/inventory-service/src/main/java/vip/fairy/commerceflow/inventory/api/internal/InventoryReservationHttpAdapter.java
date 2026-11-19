package vip.fairy.commerceflow.inventory.api.internal;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.inventory.application.service.InventoryBalanceService;
import vip.fairy.commerceflow.inventory.domain.StockAvailability;
import vip.fairy.commerceflow.inventory.domain.InventoryReservation;
import vip.fairy.commerceflow.inventory.infrastructure.transport.InventoryTrafficGate;

@RestController
public class InventoryReservationHttpAdapter {

  private final InventoryBalanceService inventory;
  private final InventoryTrafficGate traffic;

  public InventoryReservationHttpAdapter(InventoryBalanceService inventory, InventoryTrafficGate traffic) {
    this.inventory = inventory;
    this.traffic = traffic;
  }

  @GetMapping("/internal/v1/inventory/reservations/{orderId}")
  Map<Long, Integer> activeReservations(@PathVariable Long orderId) {
    traffic.requireOpen();
    return inventory.activeReservations(orderId);
  }

  @PostMapping("/internal/v1/inventory/availability")
  List<StockAvailability> availability(@RequestBody List<Long> productIds) {
    traffic.requireOpen();
    if (productIds.size() > 200 || productIds.stream().anyMatch(id -> id == null || id < 1)) {
      throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.BAD_REQUEST, "Invalid product IDs");
    }
    return inventory.availability(productIds);
  }

  @GetMapping("/internal/v1/inventory/reservations/{orderId}/allocations")
  List<InventoryReservation> allocations(@PathVariable Long orderId) {
    traffic.requireOpen();
    return inventory.activeAllocations(orderId);
  }
}
