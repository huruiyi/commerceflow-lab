package vip.fairy.commerceflow.commerce.scheduling.domain;

import java.time.Instant;

public class ScheduledTaskPolicyView {

  private String taskType;
  private boolean enabled;
  private String primaryTrigger;
  private boolean reconciliationEnabled;
  private long gracePeriodSeconds;
  private long claimLeaseSeconds;
  private int maxAttempts;
  private long retryDelaySeconds;
  private long configVersion;
  private String updatedBy;
  private Instant createdAt;
  private Instant updatedAt;

  public String getTaskType() {
    return taskType;
  }

  public void setTaskType(String taskType) {
    this.taskType = taskType;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getPrimaryTrigger() {
    return primaryTrigger;
  }

  public void setPrimaryTrigger(String primaryTrigger) {
    this.primaryTrigger = primaryTrigger;
  }

  public boolean isReconciliationEnabled() {
    return reconciliationEnabled;
  }

  public void setReconciliationEnabled(boolean value) {
    this.reconciliationEnabled = value;
  }

  public long getGracePeriodSeconds() {
    return gracePeriodSeconds;
  }

  public void setGracePeriodSeconds(long value) {
    this.gracePeriodSeconds = value;
  }

  public long getClaimLeaseSeconds() {
    return claimLeaseSeconds;
  }

  public void setClaimLeaseSeconds(long value) {
    this.claimLeaseSeconds = value;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public long getRetryDelaySeconds() {
    return retryDelaySeconds;
  }

  public void setRetryDelaySeconds(long value) {
    this.retryDelaySeconds = value;
  }

  public long getConfigVersion() {
    return configVersion;
  }

  public void setConfigVersion(long value) {
    this.configVersion = value;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }
}
