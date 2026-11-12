package lab.commerceflow.storefront.domain;

import java.time.Instant;

public class ShipmentEventView {
  private Long id;private String eventId;private String eventType;private String status;private String location;private String description;private Instant occurredAt;
  public Long getId(){return id;}public String getEventId(){return eventId;}public String getEventType(){return eventType;}public String getStatus(){return status;}public String getLocation(){return location;}public String getDescription(){return description;}public Instant getOccurredAt(){return occurredAt;}
}
