package vip.fairy.commerceflow.commerce.notification.application;

import vip.fairy.commerceflow.commerce.shared.application.ExternalServiceUnavailableException;

public class NotificationServiceUnavailableException extends ExternalServiceUnavailableException {

  public NotificationServiceUnavailableException(Throwable cause) {
    super("NOTIFICATION_SERVICE_UNAVAILABLE",
      "Notification service is temporarily unavailable", cause);
  }
}
