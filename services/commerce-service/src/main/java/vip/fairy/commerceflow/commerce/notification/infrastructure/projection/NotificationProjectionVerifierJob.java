package vip.fairy.commerceflow.commerce.notification.infrastructure.projection;

import vip.fairy.commerceflow.commerce.notification.application.NotificationProjectionVerificationService;
import vip.fairy.commerceflow.commerce.notification.infrastructure.CustomerNotificationMapper;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationProjectionVerifierJob {

  private final CustomerNotificationMapper notifications;
  private final NotificationProjectionProperties properties;
  private final NotificationProjectionVerificationService verification;

  public NotificationProjectionVerifierJob(CustomerNotificationMapper notifications,
                                           NotificationProjectionProperties properties,
                                           NotificationProjectionVerificationService verification) {
    this.notifications = notifications;
    this.properties = properties;
    this.verification = verification;
  }

  @Scheduled(fixedDelayString = "${commerceflow.notification.projection.check-delay:30s}",
    initialDelayString = "${commerceflow.notification.projection.check-initial-delay:30s}")
  public void check() {
    if (properties.isEnabled() && notifications.runningProjectionWindowId() != null) {
      verification.check();
    }
  }
}
