package lab.commerceflow.storefront.domain;

import java.time.Instant;

public class OrderEventView {
  private Long id;
  private Long actorId;
  private String eventType;
  private String fromStatus;
  private String toStatus;
  private String detail;
  private String triggerSource;
  private Long scheduledTaskId;
  private Instant createdAt;

  public Long getId(){return id;}
  public Long getActorId(){return actorId;}
  public String getEventType(){return eventType;}
  public String getFromStatus(){return fromStatus;}
  public String getToStatus(){return toStatus;}
  public String getDetail(){return detail;}
  public String getTriggerSource(){return triggerSource;}
  public Long getScheduledTaskId(){return scheduledTaskId;}
  public Instant getCreatedAt(){return createdAt;}
}
