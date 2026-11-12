package lab.commerceflow.storefront.service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import lab.commerceflow.storefront.domain.AppUser;
import lab.commerceflow.storefront.domain.PaymentView;
import lab.commerceflow.storefront.domain.ShopOrderView;
import lab.commerceflow.storefront.mapper.ShopOrderMapper;
import lab.commerceflow.storefront.mapper.ScheduledTaskMapper;
import lab.commerceflow.storefront.scheduling.ScheduledTaskTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderCancellationService {
  private final ShopOrderMapper orders;
  private final PaymentGatewayClient gateway;
  private final TransactionTemplate transactions;
  private final ScheduledTaskMapper scheduledTasks;
  private final PaymentEventPipeline events;

  public OrderCancellationService(ShopOrderMapper orders, PaymentGatewayClient gateway,
                                  TransactionTemplate transactions, ScheduledTaskMapper scheduledTasks,
                                  PaymentEventPipeline events) {
    this.orders = orders;
    this.gateway = gateway;
    this.transactions = transactions;
    this.scheduledTasks = scheduledTasks;
    this.events = events;
  }

  public void cancelByCustomer(AppUser customer, Long orderId, String reason) {
    cancel(orderId, customer.getId(), normalizeReason(reason, "Cancelled by customer"),
        "CUSTOMER", null, true);
  }

  public void cancelExpired(Long orderId, Long scheduledTaskId) {
    cancel(orderId, null, "Payment deadline expired", "SCHEDULED_TASK", scheduledTaskId, false);
  }

  private void cancel(Long orderId, Long actorId, String reason, String triggerSource,
                      Long scheduledTaskId, boolean customerRequest) {
    Begin begin = transactions.execute(status -> begin(orderId, actorId, reason, triggerSource,
        scheduledTaskId, customerRequest));
    if (begin == null || begin.done()) return;

    for (PaymentView payment : begin.pendingPayments()) gateway.close(payment);

    transactions.executeWithoutResult(status -> finish(orderId, begin.pendingPayments(), actorId,
        reason, triggerSource, scheduledTaskId, customerRequest));
  }

  private Begin begin(Long orderId, Long actorId, String reason, String triggerSource,
                      Long scheduledTaskId, boolean customerRequest) {
    ShopOrderView order = customerRequest
        ? orders.lock(actorId, orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found"))
        : orders.lockById(orderId).orElse(null);
    if (order == null || "CANCELLED".equals(order.getStatus())) return new Begin(true, List.of());
    if (!"PENDING_PAYMENT".equals(order.getStatus()) && !"CANCELLING".equals(order.getStatus())) {
      if (!customerRequest) return new Begin(true, List.of());
      throw new BusinessRuleException("Only pending orders can be cancelled");
    }
    if ("PENDING_PAYMENT".equals(order.getStatus())) {
      if (orders.startCancellation(orderId, reason) != 1) {
        throw new BusinessRuleException("Order status changed while cancelling");
      }
      orders.insertEventDetailed(orderId, actorId, "ORDER_CANCELLING", "PENDING_PAYMENT", "CANCELLING",
          reason, triggerSource, scheduledTaskId);
    }
    return new Begin(false, orders.pendingPayments(orderId));
  }

  private void finish(Long orderId, List<PaymentView> pendingPayments, Long actorId, String reason,
                      String triggerSource, Long scheduledTaskId, boolean customerRequest) {
    ShopOrderView order = orders.lockById(orderId)
        .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    if ("PAID".equals(order.getStatus()) || "CANCELLED".equals(order.getStatus())) return;
    if (!"CANCELLING".equals(order.getStatus())) {
      throw new BusinessRuleException("Order status changed while closing payments");
    }
    if (orders.successfulPaymentCount(orderId) > 0) {
      orders.restorePaidAfterCancellationRace(orderId);
      orders.insertEventDetailed(orderId, actorId, "CANCELLATION_LOST_PAYMENT_RACE", "CANCELLING", "PAID",
          "A successful payment was observed while cancelling", triggerSource, scheduledTaskId);
      return;
    }
    for (PaymentView payment : pendingPayments) {
      if (orders.closePendingPayment(payment.getId(), reason) == 1) {
        orders.insertPaymentEvent(payment.getId(), java.util.UUID.randomUUID().toString(),
            "PAYMENT_CLOSED_BY_ORDER", "PENDING", "CLOSED", reason);
      }
    }
    if (orders.finishCancellation(orderId) != 1) {
      throw new BusinessRuleException("Order status changed before cancellation completed");
    }
    orders.insertEventDetailed(orderId, actorId, "ORDER_CANCELLED", "CANCELLING", "CANCELLED",
        reason, triggerSource, scheduledTaskId);
    Map<String,Object> payload=new LinkedHashMap<>();
    payload.put("orderId",orderId);
    payload.put("reason",reason);
    payload.put("triggerSource",triggerSource);
    if(scheduledTaskId!=null) payload.put("scheduledTaskId",scheduledTaskId);
    events.appendOrder(orderId.toString(),"ORDER_CANCELLED",payload);
    if (scheduledTaskId == null) {
      scheduledTasks.byBusinessKey(ScheduledTaskTypes.PAYMENT_TIMEOUT, "order:" + orderId)
          .ifPresent(task -> {
            if (scheduledTasks.cancel(task.getId(), "Order was cancelled before its payment deadline") == 1) {
              scheduledTasks.insertEvent(task.getId(), "TASK_CANCELLED", "CUSTOMER_ACTION",
                  task.getStatus(), "CANCELLED", "Order was cancelled manually");
            }
          });
    }
  }

  private String normalizeReason(String reason, String fallback) {
    if (reason == null || reason.isBlank()) return fallback;
    String value = reason.trim();
    if (value.length() > 300) throw new IllegalArgumentException("Cancellation reason is too long");
    return value;
  }

  private record Begin(boolean done, List<PaymentView> pendingPayments) {}
}
