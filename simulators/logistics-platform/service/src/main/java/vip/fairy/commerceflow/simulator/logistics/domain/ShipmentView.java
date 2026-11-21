package vip.fairy.commerceflow.simulator.logistics.domain;

import java.time.Instant;
import java.util.List;

public record ShipmentView(Long id, String merchantShipmentNo, String platformShipmentNo, String orderNumber,
                           String carrier, String trackingNumber, String status, String callbackUrl, Instant createdAt,
                           Instant updatedAt, long shipmentVersion,
                           List<Event> events) {
  public record Summary(String merchantShipmentNo, String orderNumber, String carrier,
                        String trackingNumber, String status, Instant updatedAt) {
  }

  public record Page(List<Summary> entries, long total, int page) {
  }

  public record Event(String eventId, String eventType, String status, String location, String description,
                      Instant occurredAt) {
  }
}
