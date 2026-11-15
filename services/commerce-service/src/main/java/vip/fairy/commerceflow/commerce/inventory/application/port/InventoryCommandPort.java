package vip.fairy.commerceflow.commerce.inventory.application.port;

import vip.fairy.commerceflow.commerce.inventory.application.contract.v1.InventoryCommandV1;

import java.util.List;

public interface InventoryCommandPort {

  void handle(InventoryCommandV1 command);

  static InventoryCommandV1 reserve(String commandId, Long orderId,
                                    List<InventoryCommandV1.Item> items) {
    return InventoryCommandV1.reserve(commandId, orderId, items);
  }

  static InventoryCommandV1.Item item(Long productId, String productName, int quantity) {
    return new InventoryCommandV1.Item(productId, productName, quantity);
  }

  static InventoryCommandV1 commit(String commandId, Long orderId, String causationId) {
    return InventoryCommandV1.commit(commandId, orderId, causationId);
  }

  static InventoryCommandV1 release(String commandId, Long orderId, String reason,
                                    String causationId) {
    return InventoryCommandV1.release(commandId, orderId, reason, causationId);
  }

  static InventoryCommandV1 refund(String commandId, Long orderId, String causationId) {
    return InventoryCommandV1.refund(commandId, orderId, causationId);
  }
}
