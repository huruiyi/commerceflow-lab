package vip.fairy.commerceflow.commerce.notification.application;

import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce.NotificationMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.independent.StoreMapper;
import vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.row.NotificationStorageRecord;
import vip.fairy.commerceflow.commerce.notification.infrastructure.storage.NotificationStorageProperties;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "commerceflow.notification.storage", name = "enabled",
  havingValue = "true")
public class NotificationStorageBackfillService {

  private final NotificationMapper commerceNotifications;
  private final StoreMapper independentNotifications;
  private final NotificationStorageProperties properties;

  public NotificationStorageBackfillService(
    NotificationMapper commerceNotifications,
    StoreMapper independentNotifications,
    NotificationStorageProperties properties
  ) {
    this.commerceNotifications = commerceNotifications;
    this.independentNotifications = independentNotifications;
    this.properties = properties;
  }

  @Transactional(transactionManager = "notificationTransactionManager")
  public NotificationStorageBackfillResult copyNextBatch() {
    long previousWatermark = independentNotifications.backfillWatermark();
    List<NotificationStorageRecord> batch = commerceNotifications.storageRecordsAfter(
      previousWatermark, properties.getBackfillBatchSize());
    long currentWatermark = previousWatermark;
    for (NotificationStorageRecord notification : batch) {
      independentNotifications.insertBackfill(notification);
      independentNotifications.synchronizeBackfillState(notification);
      currentWatermark = notification.getId();
    }
    boolean completed = batch.size() < properties.getBackfillBatchSize();
    independentNotifications.advanceBackfillWatermark(currentWatermark, completed);
    return new NotificationStorageBackfillResult(previousWatermark, currentWatermark,
      batch.size(), completed);
  }
}
