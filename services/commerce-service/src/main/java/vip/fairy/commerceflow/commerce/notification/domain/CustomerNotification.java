package vip.fairy.commerceflow.commerce.notification.domain;

import java.time.Instant;

public record CustomerNotification(
  Long id,
  String eventId,
  Long orderId,
  String notificationType,
  String title,
  String message,
  String status,
  Instant readAt,
  Instant createdAt
) {

  public Long getId() {
    return id;
  }
}
