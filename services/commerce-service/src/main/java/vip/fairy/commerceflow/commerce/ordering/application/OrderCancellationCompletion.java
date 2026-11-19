package vip.fairy.commerceflow.commerce.ordering.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandPublisher;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEventPublisherPort;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.OrderCancellationCompletionMapper;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.OrderCancellationCompletionMapper.Intent;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskTypes;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.ScheduledTaskMapper;

@Service
public class OrderCancellationCompletion {

  private static final Logger LOG = LoggerFactory.getLogger(OrderCancellationCompletion.class);

  private final OrderCancellationCompletionMapper intents;
  private final OrderStorePort orders;
  private final TransactionTemplate transactions;
  private final ScheduledTaskMapper scheduledTasks;
  private final OrderEventPublisherPort events;
  private final CustomerNotificationCommandPublisher notifications;

  public OrderCancellationCompletion(OrderCancellationCompletionMapper intents, OrderStorePort orders,
                                     TransactionTemplate transactions, ScheduledTaskMapper scheduledTasks,
                                     OrderEventPublisherPort events,
                                     CustomerNotificationCommandPublisher notifications) {
    this.intents = intents;
    this.orders = orders;
    this.transactions = transactions;
    this.scheduledTasks = scheduledTasks;
    this.events = events;
    this.notifications = notifications;
  }

  void enqueue(Long orderId, Long customerId, Long actorId, String reason, String triggerSource, Long taskId) {
    intents.enqueue(new Intent(orderId, customerId, actorId, reason, triggerSource, taskId));
  }

  void complete(Long orderId) {
    transactions.executeWithoutResult(status -> {
      Intent intent = intents.lockPending(orderId);
      if (intent == null) {
        return;
      }
      // Order V1 replays the original result if its transaction committed before the local one.
      if (orders.finishCancellation("finish-cancellation:" + orderId, orderId) == 1
          && "CANCELLED".equals(orders.status(orderId))) {
        orders.insertCancellationEvent("audit-cancellation:" + orderId, orderId, intent.actorId(),
          intent.reason(), intent.triggerSource(), intent.scheduledTaskId());
        appendEffects(intent);
      }
      intents.complete(orderId);
    });
  }

  @Scheduled(initialDelayString = "${commerceflow.order.cancellation-recovery.initial-delay:2s}",
    fixedDelayString = "${commerceflow.order.cancellation-recovery.delay:5s}")
  public void recover() {
    for (Long orderId : intents.due(20)) {
      try {
        complete(orderId);
      } catch (RuntimeException failure) {
        intents.defer(orderId, Instant.now().plusSeconds(10));
        LOG.warn("Cancellation completion for order {} will retry", orderId, failure);
      }
    }
  }

  private void appendEffects(Intent intent) {
    Long orderId = intent.orderId();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", orderId);
    payload.put("customerId", intent.customerId());
    payload.put("reason", intent.reason());
    payload.put("triggerSource", intent.triggerSource());
    if (intent.scheduledTaskId() != null) {
      payload.put("scheduledTaskId", intent.scheduledTaskId());
    }
    String eventId = events.append(orderId, "ORDER_CANCELLED", payload);
    notifications.publishOrderCancelled(eventId + ":notification", intent.customerId(),
      orderId, intent.reason(), eventId);
    if (intent.scheduledTaskId() == null) {
      scheduledTasks.byBusinessKey(ScheduledTaskTypes.PAYMENT_TIMEOUT, "order:" + orderId)
        .ifPresent(task -> {
          if (scheduledTasks.cancel(task.getId(), "Order was cancelled before its payment deadline") == 1) {
            scheduledTasks.insertEvent(task.getId(), "TASK_CANCELLED", "CUSTOMER_ACTION",
              task.getStatus(), "CANCELLED", "Order was cancelled manually");
          }
        });
    }
  }
}
