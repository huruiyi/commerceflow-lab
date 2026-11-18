package vip.fairy.commerceflow.inventory.api.internal;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import vip.fairy.commerceflow.inventory.application.service.InventoryBalanceService;
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
}
