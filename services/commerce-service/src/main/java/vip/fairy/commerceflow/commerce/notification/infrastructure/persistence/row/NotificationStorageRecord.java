package vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.row;

import java.time.Instant;
import java.util.Objects;

public class NotificationStorageRecord {

  private Long id;
  private String eventId;
  private Long customerId;
  private Long orderId;
  private String notificationType;
  private String title;
  private String message;
  private String status;
  private Instant readAt;
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public String getEventId() {
    return eventId;
  }

  public Long getCustomerId() {
    return customerId;
  }

  public Long getOrderId() {
    return orderId;
  }

  public String getNotificationType() {
    return notificationType;
  }

  public String getTitle() {
    return title;
  }

  public String getMessage() {
    return message;
  }

  public String getStatus() {
    return status;
  }

  public Instant getReadAt() {
    return readAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean sameBusinessState(NotificationStorageRecord other) {
    return other != null
      && Objects.equals(customerId, other.customerId)
      && Objects.equals(orderId, other.orderId)
      && Objects.equals(notificationType, other.notificationType)
      && Objects.equals(title, other.title)
      && Objects.equals(message, other.message)
      && Objects.equals(status, other.status);
  }
}
