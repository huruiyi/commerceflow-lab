package vip.fairy.commerceflow.commerce.notification.application;

import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.independent.StoreMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.row.NotificationStorageRecord;
import vip.fairy.commerceflow.commerce.notification.infrastructure.storage.NotificationStorageProperties;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "commerceflow.notification.storage", name = "enabled",
  havingValue = "true")
public class NotificationStorageVerificationService {

  private final NotificationMapper commerceNotifications;
  private final StoreMapper independentNotifications;
  private final NotificationStorageProperties properties;

  public NotificationStorageVerificationService(
    NotificationMapper commerceNotifications,
    StoreMapper independentNotifications,
    NotificationStorageProperties properties
  ) {
    this.commerceNotifications = commerceNotifications;
    this.independentNotifications = independentNotifications;
    this.properties = properties;
  }

  @Transactional(transactionManager = "notificationTransactionManager")
  public NotificationStorageVerificationResult check() {
    Set<String> commerceEvents = new HashSet<>();
    int matched = 0;
    int missing = 0;
    int mismatch = 0;
    long afterId = 0;
    List<NotificationStorageRecord> batch;
    do {
      batch = commerceNotifications.storageRecordsAfter(afterId,
        properties.getVerificationBatchSize());
      for (NotificationStorageRecord commerce : batch) {
        commerceEvents.add(commerce.getEventId());
        NotificationStorageRecord independent =
          independentNotifications.findStorageRecordByEventId(commerce.getEventId());
        if (independent == null) {
          missing++;
        } else if (commerce.sameBusinessState(independent)) {
          matched++;
        } else {
          mismatch++;
        }
        afterId = commerce.getId();
      }
    } while (batch.size() == properties.getVerificationBatchSize());

    int orphan = countOrphans(commerceEvents);
    boolean backfillCompleted = independentNotifications.backfillCompleted();
    boolean passed = backfillCompleted
      && matched >= properties.getMinimumMatchedEvents()
      && missing == 0 && orphan == 0 && mismatch == 0;
    independentNotifications.insertStorageAudit(matched, missing, orphan, mismatch,
      backfillCompleted, passed);
    int consecutive = independentNotifications.recentPassingStorageChecks(
      properties.getRequiredConsecutivePassingChecks());
    boolean ready = consecutive >= properties.getRequiredConsecutivePassingChecks();
    return new NotificationStorageVerificationResult(properties.getMode().name(), matched,
      missing, orphan, mismatch, backfillCompleted, consecutive,
      properties.getRequiredConsecutivePassingChecks(), passed, ready, Instant.now());
  }

  @Transactional(transactionManager = "notificationTransactionManager")
  public NotificationStorageQuarantineResult quarantineOrphans() {
    if (!independentNotifications.backfillCompleted()) {
      throw new IllegalStateException("Notification history must be backfilled before quarantine");
    }
    Set<String> commerceEvents = commerceEventIds();
    int quarantined = 0;
    long afterId = 0;
    List<NotificationStorageRecord> batch;
    do {
      batch = independentNotifications.storageRecordsAfter(afterId,
        properties.getVerificationBatchSize());
      for (NotificationStorageRecord independent : batch) {
        afterId = independent.getId();
        if (!commerceEvents.contains(independent.getEventId())) {
          independentNotifications.archiveOrphan(independent.getEventId());
          quarantined += independentNotifications.deleteOrphan(independent.getEventId());
        }
      }
    } while (batch.size() == properties.getVerificationBatchSize());
    return new NotificationStorageQuarantineResult(quarantined);
  }

  private Set<String> commerceEventIds() {
    Set<String> events = new HashSet<>();
    long afterId = 0;
    List<NotificationStorageRecord> batch;
    do {
      batch = commerceNotifications.storageRecordsAfter(afterId,
        properties.getVerificationBatchSize());
      for (NotificationStorageRecord notification : batch) {
        events.add(notification.getEventId());
        afterId = notification.getId();
      }
    } while (batch.size() == properties.getVerificationBatchSize());
    return events;
  }

  private int countOrphans(Set<String> commerceEvents) {
    int orphan = 0;
    long afterId = 0;
    List<NotificationStorageRecord> batch;
    do {
      batch = independentNotifications.storageRecordsAfter(afterId,
        properties.getVerificationBatchSize());
      for (NotificationStorageRecord independent : batch) {
        if (!commerceEvents.contains(independent.getEventId())) {
          orphan++;
        }
        afterId = independent.getId();
      }
    } while (batch.size() == properties.getVerificationBatchSize());
    return orphan;
  }
}
