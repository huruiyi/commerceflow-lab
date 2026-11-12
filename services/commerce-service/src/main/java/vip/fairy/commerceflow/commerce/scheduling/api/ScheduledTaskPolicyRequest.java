package vip.fairy.commerceflow.commerce.scheduling.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record ScheduledTaskPolicyRequest(
  boolean enabled,
  @NotBlank String primaryTrigger,
  boolean reconciliationEnabled,
  @Min(0) long gracePeriodSeconds,
  @Min(1) long claimLeaseSeconds,
  @Min(1) @Max(100) int maxAttempts,
  @Min(0) long retryDelaySeconds) {
}
