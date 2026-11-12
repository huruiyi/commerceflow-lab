package lab.commerceflow.storefront.domain;

import java.time.Instant;

public class ScheduledTaskEventView {
  private Long id;
  private Long taskId;
  private String eventType;
  private String triggerSource;
  private String fromStatus;
  private String toStatus;
  private String detail;
  private Instant createdAt;
  public Long getId() { return id; }
  public Long getTaskId() { return taskId; }
  public String getEventType() { return eventType; }
  public String getTriggerSource() { return triggerSource; }
  public String getFromStatus() { return fromStatus; }
  public String getToStatus() { return toStatus; }
  public String getDetail() { return detail; }
  public Instant getCreatedAt() { return createdAt; }
}
