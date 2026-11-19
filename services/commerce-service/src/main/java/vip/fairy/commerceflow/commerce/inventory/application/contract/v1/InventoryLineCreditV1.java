package vip.fairy.commerceflow.commerce.inventory.application.contract.v1;

public interface InventoryLineCreditV1 {

  boolean credit(Credit credit, boolean apply);

  record Credit(String caseId, Long orderId, Long orderLineId, Long merchantId, Long productId,
                int quantity, String kind) {
  }
}
