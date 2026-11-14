package vip.fairy.commerceflow.commerce.notification.application;

public record NotificationStorageBackfillResult(
  long previousWatermark,
  long currentWatermark,
  int copiedCount,
  boolean completed
) {
}
