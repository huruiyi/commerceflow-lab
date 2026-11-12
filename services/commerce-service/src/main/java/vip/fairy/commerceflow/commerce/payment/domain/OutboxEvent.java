package vip.fairy.commerceflow.commerce.payment.domain;

public class OutboxEvent {
  private Long id;
  private String eventId, aggregateType, aggregateId, eventType, topic, payload, status;
  private int attempts;

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public String getAggregateId() {
    return aggregateId;
  }

  public String getEventType() {
    return eventType;
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
