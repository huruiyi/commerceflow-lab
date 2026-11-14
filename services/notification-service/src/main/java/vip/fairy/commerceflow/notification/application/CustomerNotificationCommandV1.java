package vip.fairy.commerceflow.notification.application;

import java.time.Instant;
import java.util.regex.Pattern;

public record CustomerNotificationCommandV1(
  int schemaVersion,
  String eventId,
  String eventType,
  Instant occurredAt,
  Long customerId,
  Long orderId,
  String notificationType,
  String title,
  String message,
  String correlationId,
  String causationId,
  String traceparent
) {

  public static final int VERSION = 1;
  public static final String TYPE = "CUSTOMER_NOTIFICATION_REQUESTED";
  public static final Pattern TRACEPARENT = Pattern.compile(
    "^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$");

  public CustomerNotificationCommandV1 withTraceparent(String transportTraceparent) {
    return new CustomerNotificationCommandV1(
      schemaVersion, eventId, eventType, occurredAt, customerId, orderId, notificationType,
      title, message, correlationId, causationId, transportTraceparent);
  }
}
