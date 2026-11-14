package vip.fairy.commerceflow.commerce.notification.api;

import vip.fairy.commerceflow.commerce.notification.application.NotificationStorageBackfillResult;
import vip.fairy.commerceflow.commerce.notification.application.NotificationStorageBackfillService;
import vip.fairy.commerceflow.commerce.notification.application.NotificationStorageVerificationResult;
import vip.fairy.commerceflow.commerce.notification.application.NotificationStorageVerificationService;
import vip.fairy.commerceflow.commerce.notification.application.NotificationStorageQuarantineResult;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notification-storage")
@PreAuthorize("hasRole('ADMIN')")
@ConditionalOnProperty(prefix = "commerceflow.notification.storage", name = "enabled",
  havingValue = "true")
public class NotificationStorageAdminController {

  private final NotificationStorageBackfillService backfill;
  private final NotificationStorageVerificationService verification;

  public NotificationStorageAdminController(NotificationStorageBackfillService backfill,
                                            NotificationStorageVerificationService verification) {
    this.backfill = backfill;
    this.verification = verification;
  }

  @PostMapping("/backfill")
  NotificationStorageBackfillResult backfill() {
    return backfill.copyNextBatch();
  }

  @PostMapping("/check")
  NotificationStorageVerificationResult check() {
    return verification.check();
  }

  @PostMapping("/quarantine-orphans")
  NotificationStorageQuarantineResult quarantineOrphans() {
    return verification.quarantineOrphans();
  }
}
