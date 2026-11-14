package vip.fairy.commerceflow.notification.api;

import vip.fairy.commerceflow.notification.application.CustomerNotificationCommandHandler;
import vip.fairy.commerceflow.notification.domain.NotificationShadowStatus;
import vip.fairy.commerceflow.notification.infrastructure.persistence.NotificationStoreMapper;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/shadow")
public class NotificationShadowController {

  private final NotificationStoreMapper notifications;

  public NotificationShadowController(NotificationStoreMapper notifications) {
    this.notifications = notifications;
  }

  @GetMapping("/status")
  public NotificationShadowStatus status() {
    return new NotificationShadowStatus("SHADOW",
      CustomerNotificationCommandHandler.CONSUMER_NAME,
      notifications.notificationCount(), notifications.inboxCount(
        CustomerNotificationCommandHandler.CONSUMER_NAME));
  }
}
