package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import vip.fairy.commerceflow.inventory.application.service.InventoryLineCreditService.Credit;

public interface InventoryLineCreditMapper {

  Credit find(String caseId);

  int creditedProduct(Long orderId, Long productId, String kind, long warehouseId);

  int creditedLine(Credit credit);

  int outboundQuantity(Credit credit);


  void insert(Credit credit);
}
