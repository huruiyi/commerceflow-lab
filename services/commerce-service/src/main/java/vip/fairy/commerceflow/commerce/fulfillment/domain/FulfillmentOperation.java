package vip.fairy.commerceflow.commerce.fulfillment.domain;

/**
 * 统一的履约任务快照。商家与订单标识用于授权，操作、前置版本及运单参数用于核对原请求。
 * step、status 和 errorCode 描述任务当前的执行结果。
 */
public record FulfillmentOperation(String commandId, Long orderId, Long merchantId, String operation,
                                   Long expectedShipmentVersion, String carrier, String trackingNumber,
                                   String step, ShipmentJob.Status status, String errorCode) {
}
