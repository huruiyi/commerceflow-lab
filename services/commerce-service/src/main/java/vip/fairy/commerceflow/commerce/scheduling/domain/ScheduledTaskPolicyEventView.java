package vip.fairy.commerceflow.commerce.scheduling.domain;

import java.time.Instant;

public class ScheduledTaskPolicyEventView {

  private Long id;
  private String taskType;
  private String action;
  private boolean enabled;
  private String primaryTrigger;
  private boolean reconciliationEnabled;
  private long gracePeriodSeconds;
  private long claimLeaseSeconds;
  private int maxAttempts;
  private long retryDelaySeconds;
  private long configVersion;
  private String operatedBy;
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public String getTaskType() {
    return taskType;
  }

  public String getAction() {
    return action;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getPrimaryTrigger() {
    return primaryTrigger;
  }

  public boolean isReconciliationEnabled() {
    return reconciliationEnabled;
  }

  public long getGracePeriodSeconds() {
    return gracePeriodSeconds;
  }

  public long getClaimLeaseSeconds() {
    return claimLeaseSeconds;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public long getRetryDelaySeconds() {
    return retryDelaySeconds;
  }

  public long getConfigVersion() {
    return configVersion;
  }

  public String getOperatedBy() {
    return operatedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
