package vip.fairy.commerceflow.commerce.ordering.application;

import vip.fairy.commerceflow.commerce.identity.domain.AppUser;
import vip.fairy.commerceflow.commerce.notification.application.CustomerNotificationCommandPublisher;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderEventPublisherPort;
import vip.fairy.commerceflow.commerce.ordering.domain.ShopOrderView;
import vip.fairy.commerceflow.commerce.ordering.application.port.OrderStorePort;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseOutcomeV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCloseResultV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentOrchestrationV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentWriteAdmissionV1;
import vip.fairy.commerceflow.commerce.scheduling.application.ScheduledTaskTypes;
import vip.fairy.commerceflow.commerce.scheduling.infrastructure.ScheduledTaskMapper;
import vip.fairy.commerceflow.commerce.shared.application.BusinessRuleException;
import vip.fairy.commerceflow.commerce.shared.application.ResourceNotFoundException;
import vip.fairy.commerceflow.commerce.shared.application.PaymentCancellationCrashProbe;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;

@Service
public class OrderCancellationService {

  private final OrderStorePort orders;
  private final PaymentOrchestrationV1 payments;
  private final PaymentWriteAdmissionV1 paymentWrites;
  private final TransactionTemplate transactions;
  private final ScheduledTaskMapper scheduledTasks;
  private final OrderEventPublisherPort events;
  private final CustomerNotificationCommandPublisher notifications;
  private final PaymentCancellationCrashProbe crashLab;

  public OrderCancellationService(OrderStorePort orders, PaymentOrchestrationV1 payments,
                                  PaymentWriteAdmissionV1 paymentWrites,
                                  TransactionTemplate transactions, ScheduledTaskMapper scheduledTasks,
                                  OrderEventPublisherPort events,
                                  CustomerNotificationCommandPublisher notifications,
                                  PaymentCancellationCrashProbe crashLab) {
    this.orders = orders;
    this.payments = payments;
    this.paymentWrites = paymentWrites;
    this.transactions = transactions;
    this.scheduledTasks = scheduledTasks;
    this.events = events;
    this.notifications = notifications;
    this.crashLab = crashLab;
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
    paymentWrites.requireWritable();
    Begin begin = transactions.execute(status -> begin(orderId, actorId, reason, triggerSource,
      scheduledTaskId, customerRequest));
    if (begin == null || begin.done()) {
      return;
    }

    crashLab.pause(orderId, PaymentCancellationCrashProbe.Phase.AFTER_CANCELLATION_BEGIN);
    String commandId = UUID.randomUUID().toString();
    String correlationId = UUID.randomUUID().toString();
    PaymentCloseResultV1 result = payments.closePending(new PaymentCloseCommandV1(
      PaymentCloseCommandV1.VERSION, commandId, Instant.now(), "commerce-service-ordering",
      orderId, reason, correlationId, correlationId, traceparent()));
    transactions.executeWithoutResult(status -> finish(orderId, result, actorId,
      reason, triggerSource, scheduledTaskId, customerRequest));
  }

  private Begin begin(Long orderId, Long actorId, String reason, String triggerSource,
                      Long scheduledTaskId, boolean customerRequest) {
    ShopOrderView order = customerRequest
      ? orders.lock(actorId, orderId).orElseThrow(() -> new ResourceNotFoundException("Order not found"))
      : orders.lockById(orderId).orElse(null);
    if (order == null || "CANCELLED".equals(order.getStatus())) {
      return new Begin(true);
    }
    if (!"PENDING_PAYMENT".equals(order.getStatus()) && !"CANCELLING".equals(order.getStatus())) {
      if (!customerRequest) {
        return new Begin(true);
      }
      throw new BusinessRuleException("Only pending orders can be cancelled");
    }
    if ("PENDING_PAYMENT".equals(order.getStatus())) {
      if (orders.startCancellation(orderId, reason) != 1) {
        throw new BusinessRuleException("Order status changed while cancelling");
      }
      orders.insertEventDetailed(orderId, actorId, "ORDER_CANCELLING", "PENDING_PAYMENT", "CANCELLING",
        reason, triggerSource, scheduledTaskId);
    }
    return new Begin(false);
  }

  private void finish(Long orderId, PaymentCloseResultV1 paymentResult, Long actorId, String reason,
                      String triggerSource, Long scheduledTaskId, boolean customerRequest) {
    ShopOrderView order = orders.lockById(orderId)
      .orElseThrow(() -> new ResourceNotFoundException("Order not found"));
    if ("PAID".equals(order.getStatus()) || "CANCELLED".equals(order.getStatus())) {
      return;
    }
    if (!"CANCELLING".equals(order.getStatus())) {
      throw new BusinessRuleException("Order status changed while closing payments");
    }
    if (paymentResult.outcome() == PaymentCloseOutcomeV1.PAYMENT_ALREADY_SUCCEEDED) {
      orders.restorePaidAfterCancellationRace(orderId);
      orders.insertEventDetailed(orderId, actorId, "CANCELLATION_LOST_PAYMENT_RACE", "CANCELLING", "PAID",
        "A successful payment was observed while cancelling", triggerSource, scheduledTaskId);
      return;
    }
    if (orders.finishCancellation(orderId) != 1) {
      throw new BusinessRuleException("Order status changed before cancellation completed");
    }
    orders.insertEventDetailed(orderId, actorId, "ORDER_CANCELLED", "CANCELLING", "CANCELLED",
      reason, triggerSource, scheduledTaskId);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("orderId", orderId);
    payload.put("customerId", order.getCustomerId());
    payload.put("reason", reason);
    payload.put("triggerSource", triggerSource);
    if (scheduledTaskId != null) {
      payload.put("scheduledTaskId", scheduledTaskId);
    }
    String eventId = events.append(orderId, "ORDER_CANCELLED", payload);
    notifications.publishOrderCancelled(
      eventId + ":notification",
      order.getCustomerId(),
      orderId,
      reason,
      eventId
    );
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
    if (reason == null || reason.isBlank()) {
      return fallback;
    }
    String value = reason.trim();
    if (value.length() > 300) {
      throw new IllegalArgumentException("Cancellation reason is too long");
    }
    return value;
  }

  private String traceparent() {
    String traceId = UUID.randomUUID().toString().replace("-", "");
    String spanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    return "00-" + traceId + "-" + spanId + "-01";
  }

  private record Begin(boolean done) {
  }
}
