package vip.fairy.commerceflow.commerce.logistics.application.contract;

import java.time.Instant;
import java.util.List;

public interface LogisticsPackageQueryV1 {

  List<PackageTracking> packageTracking(Long orderId);

  record PackageTracking(String merchantShipmentNo, String platformShipmentNo, String carrier,
                         String trackingNumber, String status, Instant shippedAt, Instant deliveredAt,
                         List<Event> events) {
  }

  record Event(String eventId, String eventType, String status, String location, String description,
               Instant occurredAt) {
  }
}
