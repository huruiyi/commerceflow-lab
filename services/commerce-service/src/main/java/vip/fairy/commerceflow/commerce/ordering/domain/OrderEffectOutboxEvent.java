package vip.fairy.commerceflow.commerce.ordering.domain;

public class OrderEffectOutboxEvent {

  private Long id;
  private String eventId;
  private String aggregateId;
  private String eventType;
  private String topic;
  private String payload;

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
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
}
