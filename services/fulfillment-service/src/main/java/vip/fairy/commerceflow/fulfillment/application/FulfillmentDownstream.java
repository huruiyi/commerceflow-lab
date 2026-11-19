package vip.fairy.commerceflow.fulfillment.application;

import java.util.List;

import vip.fairy.commerceflow.fulfillment.domain.ExecutionTask;

public interface FulfillmentDownstream {

  OrderFacts order(long merchantId, long orderId);

  boolean mutateOrder(String id, String operation, ExecutionTask task);

  void writeLogistics(String id, String operation, ExecutionTask task);

  boolean hasShipment(long orderId);

  List<ShipmentLine> shipmentLines(long merchantId, long orderId);

  void postOutbound(ExecutionTask task, List<ShipmentLine> lines);
  PackageReservation reservePackage(ExecutionTask task);
  boolean finishPackage(ExecutionTask task);
  WarehouseDispatch warehouseDispatch(ExecutionTask task, List<ShipmentLine> planned);
  boolean recordDispatch(ExecutionTask task, List<DispatchLine> actual);

  record PackageReservation(boolean accepted, List<ShipmentLine> lines, List<ShipmentLine> actualLines) {
  }

  record WarehouseDispatch(String status, List<DispatchLine> lines) {
  }

  record DispatchLine(long orderLineId, int quantity) {
  }

  record ShipmentLine(long orderLineId, long productId, int quantity) {
  }

  record OrderFacts(String orderNumber, String status, String inventoryStatus, String carrier,
                    String trackingNumber, long shipmentVersion) {
  }
}
