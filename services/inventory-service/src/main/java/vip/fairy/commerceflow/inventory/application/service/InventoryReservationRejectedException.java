package vip.fairy.commerceflow.inventory.application.service;

final class InventoryReservationRejectedException extends RuntimeException {

  InventoryReservationRejectedException(Long productId) {
    super("Inventory is unavailable for product " + productId);
  }
}
