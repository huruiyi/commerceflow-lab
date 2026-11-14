package vip.fairy.commerceflow.commerce.notification.domain;

public class NotificationOutboxEntry {

  private Long id;
  private String eventId;
  private String topic;
  private String payload;
  private String status;
  private int attempts;

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public String getTopic() {
    return topic;
  }

  public String getPayload() {
    return payload;
  }

  public String getStatus() {
    return status;
  }

  public int getAttempts() {
    return attempts;
  }
}
