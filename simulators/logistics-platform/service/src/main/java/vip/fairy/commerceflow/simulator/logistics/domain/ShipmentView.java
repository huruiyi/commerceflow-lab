package vip.fairy.commerceflow.simulator.logistics.domain;

import java.time.Instant;
import java.util.List;

public record ShipmentView(Long id, String merchantShipmentNo, String platformShipmentNo, String orderNumber,
                           String carrier, String trackingNumber, String status, String callbackUrl, Instant createdAt,
                           Instant updatedAt,
                           List<Event> events) {
  public record Event(String eventId, String eventType, String status, String location, String description,
                      Instant occurredAt) {
  }
}
