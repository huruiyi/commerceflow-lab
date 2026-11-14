package vip.fairy.commerceflow.commerce.notification.application;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 通知模块的第一版写入契约。传输层可在保持字段语义的前提下使用进程内调用或消息中间件。
 */
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

  public static CustomerNotificationCommandV1 orderCancelled(String eventId, Long customerId,
                                                               Long orderId, String reason,
                                                               String causationId) {
    return orderCancelled(eventId, customerId, orderId, reason, causationId, newTraceparent());
  }

  public CustomerNotificationCommandV1 withTraceparent(String transportTraceparent) {
    return new CustomerNotificationCommandV1(
      schemaVersion, eventId, eventType, occurredAt, customerId, orderId, notificationType,
      title, message, correlationId, causationId, transportTraceparent);
  }

  public static CustomerNotificationCommandV1 orderCancelled(String eventId, Long customerId,
                                                               Long orderId, String reason,
                                                               String causationId, String traceparent) {
    return new CustomerNotificationCommandV1(
      VERSION,
      eventId,
      TYPE,
      Instant.now(),
      customerId,
      orderId,
      "ORDER_CANCELLED",
      "Order cancelled",
      reason,
      causationId,
      causationId,
      traceparent
    );
  }

  private static String newTraceparent() {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }
}
