package vip.fairy.commerceflow.fulfillment.domain;

import vip.fairy.commerceflow.fulfillment.domain.FulfillmentCommand.Operation;

/** 已受理任务的原始参数与当前持久化步骤。 */
public record ExecutionTask(String commandId, long orderId, long merchantId, Operation operation, String orderNumber,
                            String carrier, String trackingNumber, Long expectedVersion, String detail,
                            String step, String status, String errorCode) {

  public boolean shipment() {
    return operation == Operation.SHIP;
  }
}
