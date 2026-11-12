package vip.fairy.commerceflow.commerce.notification.domain;

import java.time.Instant;

public class CustomerNotification {
  private Long id;
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
}
