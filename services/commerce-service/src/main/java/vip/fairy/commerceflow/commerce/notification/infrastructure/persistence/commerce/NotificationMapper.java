package vip.fairy.commerceflow.commerce.notification.infrastructure.persistence.commerce;

import java.time.Instant;
import java.util.List;

import vip.fairy.commerceflow.commerce.notification.domain.NotificationOutboxEntry;
import org.apache.ibatis.annotations.Param;

public interface NotificationMapper {

  int insertOutbox(@Param("eventId") String eventId, @Param("topic") String topic,
                   @Param("payload") String payload);


  List<NotificationOutboxEntry> pendingOutbox(@Param("limit") int limit);


  int markOutboxPublished(Long id);


  int markOutboxRetry(@Param("id") Long id, @Param("nextAttemptAt") Instant nextAttemptAt,
                      @Param("error") String error);


  int markOutboxDead(@Param("id") Long id, @Param("error") String error);


  int replayDeadOutbox(@Param("eventId") String eventId);
}
