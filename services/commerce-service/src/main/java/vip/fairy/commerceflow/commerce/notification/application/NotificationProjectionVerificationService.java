package vip.fairy.commerceflow.commerce.notification.application;

import vip.fairy.commerceflow.commerce.notification.infrastructure.CustomerNotificationMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.projection.NotificationProjectionProperties;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationProjectionVerificationService {

  private final CustomerNotificationMapper notifications;
  private final NotificationProjectionProperties properties;

  public NotificationProjectionVerificationService(CustomerNotificationMapper notifications,
                                                   NotificationProjectionProperties properties) {
    this.notifications = notifications;
    this.properties = properties;
  }

  @Transactional
  public Long start() {
    if (notifications.startProjectionWindow() != 1) {
      throw new IllegalStateException("A notification projection window is already active");
    }
    return requireActiveWindow();
  }

  @Transactional
  public NotificationProjectionVerificationResult check() {
    Long windowId = requireActiveWindow();
    Long startNotificationId = notifications.projectionWindowStartNotificationId(windowId);
    int matched = notifications.matchedProjectionCount(windowId, startNotificationId);
    int missingShadow = notifications.missingShadowProjectionCount(windowId, startNotificationId);
    int orphanShadow = notifications.orphanShadowProjectionCount(windowId);
    int mismatch = notifications.mismatchedProjectionCount(windowId, startNotificationId);
    int blockingOutbox = notifications.blockingOutboxCount(windowId);
    boolean passed = matched >= properties.getMinimumMatchedEvents()
      && missingShadow == 0 && orphanShadow == 0 && mismatch == 0 && blockingOutbox == 0;
    notifications.insertProjectionAudit(windowId, matched, missingShadow, orphanShadow,
      mismatch, blockingOutbox, passed);
    int consecutive = notifications.recentPassingProjectionChecks(windowId,
      properties.getRequiredConsecutivePassingChecks());
    boolean ready = consecutive >= properties.getRequiredConsecutivePassingChecks();
    if (ready) {
      notifications.markProjectionWindowReady(windowId);
    } else {
      notifications.markProjectionWindowRunning(windowId);
    }
    return new NotificationProjectionVerificationResult(
      windowId,
      notifications.projectionWindowStatus(windowId),
      matched,
      missingShadow,
      orphanShadow,
      mismatch,
      blockingOutbox,
      consecutive,
      properties.getRequiredConsecutivePassingChecks(),
      properties.getMinimumMatchedEvents(),
      passed,
      ready,
      Instant.now()
    );
  }

  @Transactional
  public void stop(Long windowId) {
    if (notifications.stopProjectionWindow(windowId) != 1) {
      throw new IllegalStateException("Only a READY notification projection window can stop");
    }
  }

  private Long requireActiveWindow() {
    Long windowId = notifications.runningProjectionWindowId();
    if (windowId == null) {
      throw new IllegalStateException("No active notification projection window");
    }
    return windowId;
  }
}
