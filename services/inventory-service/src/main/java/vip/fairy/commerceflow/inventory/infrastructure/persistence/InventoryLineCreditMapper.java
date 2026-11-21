package vip.fairy.commerceflow.inventory.infrastructure.persistence;

import java.util.List;
import vip.fairy.commerceflow.inventory.application.service.InventoryLineCreditService.Credit;
import vip.fairy.commerceflow.inventory.application.service.InventoryLineCreditService.Origin;
import vip.fairy.commerceflow.inventory.application.service.InventoryLineCreditService.Source;

public interface InventoryLineCreditMapper {

  String replacementStatus(String caseId);

  void insertReplacement(String caseId);

  void shipReplacement(String caseId);

  Credit find(String caseId);

  int creditedProduct(Long orderId, Long productId, String kind, long warehouseId);

  int creditedLine(Credit credit);

  int outboundQuantity(Credit credit);
  List<Source> sources(Origin request);


  void insert(Credit credit);
}
