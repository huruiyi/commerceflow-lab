package vip.fairy.commerceflow.commerce.fulfillment.domain;

/**
 * 持久发货操作。orderId 和 merchantId 为外部边界标识，commandId 用于派生下游命令 ID。
 * carrier 和 trackingNumber 保留受理时的参数，step 与 status 共同描述下一步和处理结果。
 */
public record ShipmentJob(Long orderId, String commandId, Long merchantId, String orderNumber,
                          String carrier, String trackingNumber, Step step, Status status, String lastError) {

  public enum Step {
    RESERVE, WAREHOUSE, LOGISTICS, FINISH_ORDER
  }

  public enum Status {
    PENDING, COMPLETED, REJECTED, REVIEW_REQUIRED
  }
}
