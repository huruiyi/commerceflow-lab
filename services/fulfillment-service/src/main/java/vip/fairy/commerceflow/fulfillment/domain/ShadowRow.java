package vip.fairy.commerceflow.fulfillment.domain;

/** 从一致性快照读取的一行，payload 为源表完整业务列。 */
public record ShadowRow(String tableName, String rowKey, String payload) {

  public ShadowKey key() {
    return new ShadowKey(tableName, rowKey);
  }
}
