package vip.fairy.commerceflow.commerce.notification.application;

import java.time.Instant;

public record NotificationProjectionVerificationResult(
  Long windowId,
  String windowStatus,
  int matchedCount,
  int missingShadowCount,
  int orphanShadowCount,
  int mismatchCount,
  int blockingOutboxCount,
  int consecutivePassingChecks,
  int requiredConsecutivePassingChecks,
  int minimumMatchedEvents,
  boolean passed,
  boolean readyToStopWindow,
  Instant checkedAt
) {
}
