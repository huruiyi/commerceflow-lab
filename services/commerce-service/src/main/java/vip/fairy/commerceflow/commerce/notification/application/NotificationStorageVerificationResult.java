package vip.fairy.commerceflow.commerce.notification.application;

import java.time.Instant;

public record NotificationStorageVerificationResult(
  String mode,
  int matchedCount,
  int missingCount,
  int orphanCount,
  int mismatchCount,
  boolean backfillCompleted,
  int consecutivePassingChecks,
  int requiredConsecutivePassingChecks,
  boolean passed,
  boolean readyToCutOver,
  Instant checkedAt
) {
}
