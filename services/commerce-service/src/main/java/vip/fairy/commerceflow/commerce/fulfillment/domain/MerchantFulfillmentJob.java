package vip.fairy.commerceflow.commerce.fulfillment.domain;

/**
 * 商家履约操作快照。commandId 是原操作标识，orderId 和 merchantId 对应外部业务归属。
 * expectedVersion、carrier、trackingNumber 和 detail 保留受理时的请求与事件内容。
 * step、status 和 lastError 表示可恢复的执行位置和结果。
 */
public record MerchantFulfillmentJob(String commandId, Long orderId, Long merchantId, Operation operation,
                                    long expectedVersion, String carrier, String trackingNumber, String detail,
                                    Step step, ShipmentJob.Status status, String lastError) {

  public enum Operation {
    CONFIRM, UPDATE_SHIPMENT
  }

  public enum Step {
    CHANNEL, ORDER
  }
}
