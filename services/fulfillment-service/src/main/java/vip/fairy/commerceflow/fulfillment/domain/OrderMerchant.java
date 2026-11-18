package vip.fairy.commerceflow.fulfillment.domain;

/** 订单服务提供的归属关系。 */
public record OrderMerchant(long orderId, long merchantId) {
}
