package vip.fairy.commerceflow.commerce.notification.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandV1;
import vip.fairy.commerceflow.commerce.notification.application.OffsiteWorkOrderPublisher;
import vip.fairy.commerceflow.commerce.notification.domain.NotificationOutboxEntry;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationOutboxDispatcher {

  private final NotificationMapper notifications;
  private final CustomerNotificationKafkaTransport kafka;
  private final NotificationDeliveryProperties properties;
  private final ObjectMapper mapper;
  private final OffsiteNotificationClient offsite;
  private final Path pauseFile;
  private final AtomicInteger paused = new AtomicInteger(-1);

  public NotificationOutboxDispatcher(NotificationMapper notifications,
                                      CustomerNotificationKafkaTransport kafka,
                                      NotificationDeliveryProperties properties,
                                      ObjectMapper mapper, OffsiteNotificationClient offsite,
                                      @Value("${commerceflow.offsite.dispatch-pause-file:}") String pauseFile,
                                      MeterRegistry registry) {
    this.notifications = notifications;
    this.kafka = kafka;
    this.properties = properties;
    this.mapper = mapper;
    this.offsite = offsite;
    this.pauseFile = pauseFile.isBlank() ? null : Path.of(pauseFile).toAbsolutePath();
    registry.gauge("commerceflow.notification.offsite.dispatch.paused", paused);
  }

  @Scheduled(fixedDelayString = "${commerceflow.notification.delivery.publish-delay:1s}",
    initialDelayString = "${commerceflow.notification.delivery.publish-initial-delay:2s}")
  public void dispatch() {
    boolean hold = pauseFile != null &&
      (!Files.isDirectory(pauseFile.getParent()) || !Files.notExists(pauseFile));
    // A fixed-delay tick acknowledges the pause only after the previous batch returned.
    paused.set(hold ? 1 : 0);
    for (NotificationOutboxEntry entry : notifications.pendingOutbox(50, hold)) {
      deliver(entry);
    }
  }

  public int replayDead(String eventId) {
    return notifications.replayDeadOutbox(eventId);
  }

  private void deliver(NotificationOutboxEntry entry) {
    try {
      if (OffsiteWorkOrderPublisher.TOPIC.equals(entry.getTopic())) {
        offsite.send(entry.getPayload());
      } else {
        var command = mapper.readValue(entry.getPayload(), CustomerNotificationCommandV1.class);
        kafka.send(entry.getTopic(), command);
      }
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
