package lab.commerceflow.storefront.domain;

import java.time.Instant;

public class ScheduledTaskView {
  private Long id;
  private String taskKey;
  private String taskType;
  private String aggregateType;
  private String aggregateId;
  private String payloadJson;
  private String status;
  private Instant dueAt;
  private String primaryTriggerSnapshot;
  private boolean reconciliationEnabledSnapshot;
  private long gracePeriodSecondsSnapshot;
  private long claimLeaseSecondsSnapshot;
  private int maxAttempts;
  private long retryDelaySecondsSnapshot;
  private long policyVersionSnapshot;
  private int attempts;
  private Instant leaseUntil;
  private String claimedBy;
  private String lastTriggerSource;
  private String lastError;
  private Instant completedAt;
  private Instant cancelledAt;
  private int version;
  private Instant createdAt;
  private Instant updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getTaskKey() { return taskKey; }
  public void setTaskKey(String taskKey) { this.taskKey = taskKey; }
  public String getTaskType() { return taskType; }
  public void setTaskType(String taskType) { this.taskType = taskType; }
  public String getAggregateType() { return aggregateType; }
  public void setAggregateType(String aggregateType) { this.aggregateType = aggregateType; }
  public String getAggregateId() { return aggregateId; }
  public void setAggregateId(String aggregateId) { this.aggregateId = aggregateId; }
  public String getPayloadJson() { return payloadJson; }
  public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Instant getDueAt() { return dueAt; }
  public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
  public String getPrimaryTriggerSnapshot() { return primaryTriggerSnapshot; }
  public void setPrimaryTriggerSnapshot(String value) { this.primaryTriggerSnapshot = value; }
  public boolean isReconciliationEnabledSnapshot() { return reconciliationEnabledSnapshot; }
  public void setReconciliationEnabledSnapshot(boolean value) { this.reconciliationEnabledSnapshot = value; }
  public long getGracePeriodSecondsSnapshot() { return gracePeriodSecondsSnapshot; }
  public void setGracePeriodSecondsSnapshot(long value) { this.gracePeriodSecondsSnapshot = value; }
  public long getClaimLeaseSecondsSnapshot() { return claimLeaseSecondsSnapshot; }
  public void setClaimLeaseSecondsSnapshot(long value) { this.claimLeaseSecondsSnapshot = value; }
  public int getMaxAttempts() { return maxAttempts; }
  public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
  public long getRetryDelaySecondsSnapshot() { return retryDelaySecondsSnapshot; }
  public void setRetryDelaySecondsSnapshot(long value) { this.retryDelaySecondsSnapshot = value; }
  public long getPolicyVersionSnapshot() { return policyVersionSnapshot; }
  public void setPolicyVersionSnapshot(long value) { this.policyVersionSnapshot = value; }
  public int getAttempts() { return attempts; }
  public void setAttempts(int attempts) { this.attempts = attempts; }
  public Instant getLeaseUntil() { return leaseUntil; }
  public void setLeaseUntil(Instant leaseUntil) { this.leaseUntil = leaseUntil; }
  public String getClaimedBy() { return claimedBy; }
  public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
  public String getLastTriggerSource() { return lastTriggerSource; }
  public void setLastTriggerSource(String value) { this.lastTriggerSource = value; }
  public String getLastError() { return lastError; }
  public void setLastError(String lastError) { this.lastError = lastError; }
  public Instant getCompletedAt() { return completedAt; }
  public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
  public Instant getCancelledAt() { return cancelledAt; }
  public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
  public int getVersion() { return version; }
  public void setVersion(int version) { this.version = version; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
