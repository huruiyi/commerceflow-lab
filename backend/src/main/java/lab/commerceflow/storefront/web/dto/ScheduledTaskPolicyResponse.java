package lab.commerceflow.storefront.web.dto;

import lab.commerceflow.storefront.scheduling.ResolvedTaskPolicy;

public record ScheduledTaskPolicyResponse(
    String taskType,
    boolean enabled,
    String primaryTrigger,
    boolean reconciliationEnabled,
    long gracePeriodSeconds,
    long claimLeaseSeconds,
    int maxAttempts,
    long retryDelaySeconds,
    long configVersion,
    String source,
    boolean handlerAvailable) {

  public static ScheduledTaskPolicyResponse from(ResolvedTaskPolicy policy) {
    return new ScheduledTaskPolicyResponse(policy.taskType(), policy.enabled(),
        policy.primaryTrigger().name(), policy.reconciliationEnabled(), policy.gracePeriodSeconds(),
        policy.claimLeaseSeconds(), policy.maxAttempts(), policy.retryDelaySeconds(),
        policy.version(), policy.source(), policy.handlerAvailable());
  }
}
