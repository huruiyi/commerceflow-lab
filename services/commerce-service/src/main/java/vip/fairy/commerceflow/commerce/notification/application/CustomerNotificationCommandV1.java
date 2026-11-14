package vip.fairy.commerceflow.commerce.notification.application;

import java.time.Instant;

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
  String causationId
) {

  public static final int VERSION = 1;
  public static final String TYPE = "CUSTOMER_NOTIFICATION_REQUESTED";
}
