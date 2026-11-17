package vip.fairy.commerceflow.inventory.application.service;

public class InventoryNotFoundException extends RuntimeException {

  public InventoryNotFoundException(String message) {
    super(message);
  }
}
