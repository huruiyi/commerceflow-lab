package vip.fairy.commerceflow.inventory.domain;

import org.apache.ibatis.annotations.AutomapConstructor;

/** Inventory 对订单商品预占的独立状态。 */
public record InventoryReservation(
  Long id,
  Long productId,
  int quantity,
  String status,
  long warehouseId
) {

  @AutomapConstructor
  public InventoryReservation {
  }

  public InventoryReservation(Long id, Long productId, int quantity, String status) {
    this(id, productId, quantity, status, 1);
  }
}
