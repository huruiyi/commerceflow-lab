package vip.fairy.commerceflow.fulfillment.domain;

/** 已提交的快照代次和源事务水位。 */
public record Watermark(long generation, long sourceSequence) {
}
