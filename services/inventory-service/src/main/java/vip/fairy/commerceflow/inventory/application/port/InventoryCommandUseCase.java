package vip.fairy.commerceflow.inventory.application.port;

import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandResultV1;
import vip.fairy.commerceflow.inventory.application.contract.v1.InventoryCommandV1;

public interface InventoryCommandUseCase {

  InventoryCommandResultV1 handle(InventoryCommandV1 command);
}
