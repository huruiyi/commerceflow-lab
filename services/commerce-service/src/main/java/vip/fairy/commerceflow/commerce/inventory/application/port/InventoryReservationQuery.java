package vip.fairy.commerceflow.commerce.inventory.application.port;

import java.util.Map;

public interface InventoryReservationQuery {

  Map<Long, Integer> activeReservations(Long orderId);
}
