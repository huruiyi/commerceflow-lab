package lab.commerceflow.storefront.scheduling;

public record ResolvedTaskPolicy(
    String taskType,
    boolean enabled,
    TriggerStrategy primaryTrigger,
    boolean reconciliationEnabled,
    long gracePeriodSeconds,
    long claimLeaseSeconds,
    int maxAttempts,
    long retryDelaySeconds,
    long version,
    String source,
    boolean handlerAvailable) {
}
