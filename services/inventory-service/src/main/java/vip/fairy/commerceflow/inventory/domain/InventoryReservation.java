package vip.fairy.commerceflow.inventory.domain;

/** Inventory 对订单商品预占的独立状态。 */
public record InventoryReservation(
  Long id,
  Long productId,
  int quantity,
  String status
) {
}
