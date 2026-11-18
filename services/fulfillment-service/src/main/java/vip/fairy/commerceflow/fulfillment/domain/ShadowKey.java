package vip.fairy.commerceflow.fulfillment.domain;

/** 影子表与其原始主键。 */
public record ShadowKey(String tableName, String rowKey) {
}
