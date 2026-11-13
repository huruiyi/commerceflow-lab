package vip.fairy.commerceflow.commerce.receipt.application;

import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.OrderLifecycleProperties;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.ShopOrderMapper;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskService;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskTypes;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.ScheduledTaskMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
public class OrderReceiptLifecycleService {
  private final ShopOrderMapper orders;
  private final ScheduledTaskMapper taskMapper;
  private final ObjectProvider<ScheduledTaskService> scheduledTasks;
  private final OrderLifecycleProperties properties;

  public OrderReceiptLifecycleService(ShopOrderMapper orders, ScheduledTaskMapper taskMapper,
                                      ObjectProvider<ScheduledTaskService> scheduledTasks,
                                      OrderLifecycleProperties properties) {
    this.orders = orders;
    this.taskMapper = taskMapper;
    this.scheduledTasks = scheduledTasks;
    this.properties = properties;
  }

  @Transactional
  public void scheduleAutoReceive(Long orderId, Instant deliveredAt) {
    scheduledTasks.getObject().scheduleIfEnabled(ScheduledTaskTypes.AUTO_RECEIVE,
      taskKey(orderId), "ORDER", orderId.toString(),
      deliveredAt.plus(properties.getAutoReceiveAfterDelivery()),
      Map.of("orderId", orderId, "deliveredAt", deliveredAt.toString()));
  }

  @Transactional
  public void confirmReceipt(Long customerId, Long actorId, Long orderId) {
    ShopOrderView order = orders.lock(customerId, orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    if ("COMPLETED".equals(order.getStatus())) {
      return;
    }
    if ("RECEIVED".equals(order.getStatus())) {
      scheduleAutoComplete(orderId, order.getReceivedAt());
      return;
    }
    if (!"SHIPPED".equals(order.getStatus())) {
      throw new BusinessRuleException("Only shipped orders can be received");
    }
    Instant receivedAt = Instant.now();
    if (orders.markReceived(orderId, receivedAt) == 1) {
      orders.insertEventDetailed(orderId, actorId, "ORDER_RECEIVED", "SHIPPED", "RECEIVED",
        "Customer confirmed receipt", "USER", null);
      cancelPendingAutoReceive(orderId, "Customer confirmed receipt before automatic receipt");
      scheduleAutoComplete(orderId, receivedAt);
    }
  }

  @Transactional
  public void autoReceive(Long orderId, Long scheduledTaskId) {
    ShopOrderView order = orders.lockById(orderId).orElse(null);
    if (order == null || "COMPLETED".equals(order.getStatus())) {
      return;
    }
    if ("RECEIVED".equals(order.getStatus())) {
      scheduleAutoComplete(orderId, order.getReceivedAt());
      return;
    }
    if (!"SHIPPED".equals(order.getStatus())) {
      return;
    }
    Instant receivedAt = Instant.now();
    if (orders.markReceived(orderId, receivedAt) == 1) {
      orders.insertEventDetailed(orderId, null, "ORDER_AUTO_RECEIVED", "SHIPPED", "RECEIVED",
        "Automatic receipt after logistics delivery observation period", "SCHEDULED_TASK",
        scheduledTaskId);
      scheduleAutoComplete(orderId, receivedAt);
    }
  }

  @Transactional
  public void autoComplete(Long orderId, Long scheduledTaskId) {
    ShopOrderView order = orders.lockById(orderId).orElse(null);
    if (order == null || "COMPLETED".equals(order.getStatus())
      || !"RECEIVED".equals(order.getStatus())) {
      return;
    }
    Instant completedAt = Instant.now();
    if (orders.markCompleted(orderId, completedAt) == 1) {
      orders.insertEventDetailed(orderId, null, "ORDER_AUTO_COMPLETED", "RECEIVED", "COMPLETED",
        "Order completed after receipt observation period", "SCHEDULED_TASK", scheduledTaskId);
    }
  }

  private void scheduleAutoComplete(Long orderId, Instant receivedAt) {
    if (receivedAt == null) {
      return;
    }
    scheduledTasks.getObject().scheduleIfEnabled(ScheduledTaskTypes.AUTO_COMPLETE,
      taskKey(orderId), "ORDER", orderId.toString(),
      receivedAt.plus(properties.getAutoCompleteAfterReceipt()),
      Map.of("orderId", orderId, "receivedAt", receivedAt.toString()));
  }

  private void cancelPendingAutoReceive(Long orderId, String reason) {
    taskMapper.byBusinessKey(ScheduledTaskTypes.AUTO_RECEIVE, taskKey(orderId)).ifPresent(task -> {
      if (taskMapper.cancel(task.getId(), reason) == 1) {
        taskMapper.insertEvent(task.getId(), "TASK_CANCELLED", "USER_ACTION", task.getStatus(),
          "CANCELLED", reason);
      }
    });
  }

  private String taskKey(Long orderId) {
    return "order:" + orderId;
  }
}
