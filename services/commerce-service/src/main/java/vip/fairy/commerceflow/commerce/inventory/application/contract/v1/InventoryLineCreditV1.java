package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

import java.util.List;

public interface InventoryLineCreditV1 {

  boolean credit(Credit credit, boolean apply);
  List<Source> sources(Origin request);

  record Origin(long orderId, long orderLineId, long merchantId, long productId,
                Long warehouseId, String outboundId) {
  }

  record Source(long warehouseId, String outboundId, int quantity) {
  }

  record Credit(String caseId, Long orderId, Long orderLineId, Long merchantId, Long productId,
                int quantity, String kind, Long warehouseId, String outboundId) {
  }
}
