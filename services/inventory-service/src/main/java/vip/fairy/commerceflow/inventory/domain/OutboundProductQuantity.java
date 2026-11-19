package vip.fairy.commerceflow.inventory.domain;

/** 同一订单中指定商品所有已记账包裹的累计出库件数。 */
public record OutboundProductQuantity(long productId, long quantity) {
}
