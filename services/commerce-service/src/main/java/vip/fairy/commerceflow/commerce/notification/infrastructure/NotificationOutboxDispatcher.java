package vip.fairy.commerceflow.commerce.notification.infrastructure;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.commerce.notification.domain.NotificationOutboxEntry;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;

import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationOutboxDispatcher {

  private final NotificationMapper notifications;
  private final CustomerNotificationKafkaTransport kafka;
  private final NotificationDeliveryProperties properties;
  private final ObjectMapper mapper;

  public NotificationOutboxDispatcher(NotificationMapper notifications,
                                      CustomerNotificationKafkaTransport kafka,
                                      NotificationDeliveryProperties properties,
                                      ObjectMapper mapper) {
    this.notifications = notifications;
    this.kafka = kafka;
    this.properties = properties;
    this.mapper = mapper;
  }

  @Scheduled(fixedDelayString = "${commerceflow.notification.delivery.publish-delay:1s}",
    initialDelayString = "${commerceflow.notification.delivery.publish-initial-delay:2s}")
  public void dispatch() {
    for (NotificationOutboxEntry entry : notifications.pendingOutbox(50)) {
      deliver(entry);
    }
  }

  public int replayDead(String eventId) {
    return notifications.replayDeadOutbox(eventId);
  }

  private void deliver(NotificationOutboxEntry entry) {
    try {
      CustomerNotificationCommandV1 command = mapper.readValue(entry.getPayload(),
        CustomerNotificationCommandV1.class);
      kafka.send(entry.getTopic(), command);
      notifications.markOutboxPublished(entry.getId());
    } catch (Exception exception) {
      fail(entry, exception);
    }
  }

  private void fail(NotificationOutboxEntry entry, Exception exception) {
    String error = abbreviate(exception.getMessage());
    if (entry.getAttempts() + 1 < properties.getMaxAttempts()) {
      notifications.markOutboxRetry(entry.getId(),
        Instant.now().plus(properties.getRetryDelay()), error);
      return;
    }
    notifications.markOutboxDead(entry.getId(), error);
  }

  private String abbreviate(String message) {
    if (message == null || message.isBlank()) {
      return "Unknown notification delivery failure";
    }
    return message.length() <= 500 ? message : message.substring(0, 500);
  }
}
