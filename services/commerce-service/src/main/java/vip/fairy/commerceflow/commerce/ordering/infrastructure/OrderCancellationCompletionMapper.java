package vip.fairy.commerceflow.commerce.ordering.infrastructure;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Param;

public interface OrderCancellationCompletionMapper {

  int enqueue(Intent intent);

  List<Long> due(int limit);

  Intent lockPending(Long orderId);

  int complete(Long orderId);

  int defer(@Param("orderId") Long orderId, @Param("nextAttemptAt") Instant nextAttemptAt);

  /**
   * Cancellation context captured after payment close and before the terminal Order V1 mutation.
   *
   * @param orderId authoritative Order V1 identifier
   * @param customerId notification recipient
   * @param actorId customer who cancelled, or null for a scheduled cancellation
   * @param reason cancellation text shared by audit and notifications
   * @param triggerSource CUSTOMER or SCHEDULED_TASK
   * @param scheduledTaskId initiating payment timeout task, or null for a customer cancellation
   */
  record Intent(Long orderId, Long customerId, Long actorId, String reason,
                String triggerSource, Long scheduledTaskId) {
  }
}
