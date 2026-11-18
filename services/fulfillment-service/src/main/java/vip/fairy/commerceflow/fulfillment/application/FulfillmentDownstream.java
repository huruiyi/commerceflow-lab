package vip.fairy.commerceflow.fulfillment.application;

import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;

public interface FulfillmentDownstream {

  OrderFacts order(long merchantId, long orderId);

  boolean mutateOrder(String id, String operation, ExecutionTask task);

  void writeLogistics(String id, String operation, ExecutionTask task);

  boolean hasShipment(long orderId);

  record OrderFacts(String orderNumber, String status, String inventoryStatus, String carrier,
                    String trackingNumber, long shipmentVersion) {
  }
}
