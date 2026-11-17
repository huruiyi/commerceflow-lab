package vip.fairy.commerceflow.commerce.ordering.application.port;

import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;

import java.time.Instant;
import java.util.Optional;

public interface OrderReceiptPort {

  Optional<ShopOrderView> lock(Long customerId, Long orderId);

  Optional<ShopOrderView> lock(Long orderId);

  boolean received(Long orderId, Instant receivedAt);

  boolean completed(Long orderId, Instant completedAt);

  void transitionRecorded(Long orderId, Long actorId, String eventType,
                          String fromStatus, String toStatus, String detail,
                          String triggerSource, Long scheduledTaskId);
}
