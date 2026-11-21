package vip.fairy.commerceflow.payment.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.infrastructure.OffsiteNotificationClient;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultTypeV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultV1;
import vip.fairy.commerceflow.payment.infrastructure.ChannelGatewayClient;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;
import vip.fairy.commerceflow.payment.infrastructure.RemoteInventoryEffectClient;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient.OrderView;
import vip.fairy.commerceflow.payment.infrastructure.persistence.PaymentEffectMapper;

@Service
public class PaymentEffectDispatcher {

  private final PaymentEffectMapper store;
  private final ObjectMapper mapper;
  private final PaymentShadowProperties properties;
  private final RemoteOrderEffectClient orders;
  private final RemoteInventoryEffectClient inventory;
  private final RefundApplicationService refunds;
  private final ChannelGatewayClient gateway;
  private final OffsiteNotificationClient offsite;
  private final Path pauseFile;
  private final AtomicInteger paused = new AtomicInteger(-1);

  public PaymentEffectDispatcher(PaymentEffectMapper store, ObjectMapper mapper,
                                 PaymentShadowProperties properties,
                                 RemoteOrderEffectClient orders,
                                 RemoteInventoryEffectClient inventory,
                                 RefundApplicationService refunds,
                                 ChannelGatewayClient gateway,
                                 OffsiteNotificationClient offsite,
                                 @Value("${commerceflow.offsite.dispatch-pause-file:}") String pauseFile,
                                 MeterRegistry registry) {
    this.store = store;
    this.mapper = mapper;
    this.properties = properties;
    this.orders = orders;
    this.inventory = inventory;
    this.refunds = refunds;
    this.gateway = gateway;
    this.offsite = offsite;
    this.pauseFile = pauseFile.isBlank() ? null : Path.of(pauseFile).toAbsolutePath();
    registry.gauge("commerceflow.notification.offsite.dispatch.paused", paused);
  }

  @Scheduled(fixedDelayString = "${commerceflow.payment.effects.dispatch-delay:1s}")
  public void dispatchEffects() {
    if (!enabled()) return;
    boolean hold = pauseFile != null &&
      (!Files.isDirectory(pauseFile.getParent()) || !Files.notExists(pauseFile));
    // A fixed-delay tick acknowledges the pause only after the previous batch returned.
    paused.set(hold ? 1 : 0);
    List<Long> ids = store.readyEffectIds(properties.effects().batchSize(), hold);
    for (Long id : ids) dispatchEffect(id);
  }

  @Scheduled(fixedDelayString = "${commerceflow.payment.effects.command-delay:1s}")
  public void dispatchRefundCommands() {
    if (!enabled()) return;
    List<String> ids = store.readyRefundCommandIds(properties.effects().batchSize());
    for (String id : ids) dispatchRefund(id);
  }

  private void dispatchEffect(Long id) {
    int claimed = store.claimEffect(id);
    if (claimed != 1) return;
    try {
      Map<String,Object> row = store.findEffect(id);
      apply(String.valueOf(row.get("event_id")), String.valueOf(row.get("event_type")),
        String.valueOf(row.get("payload")));
      store.markEffectPublished(id);
    } catch (RuntimeException exception) {
      store.retryEffect(error(exception), id);
    }
  }

  void apply(String eventId, String eventType, String payload) {
    if ("OFFSITE_REFUND_SUCCEEDED".equals(eventType)) {
      offsite.send(payload);
      return;
    }
    if ("REFUND_REQUESTED".equals(eventType)) {
      applyRefundRequested(eventId, mapper.readTree(payload));
      return;
    }
    JsonNode node = mapper.readTree(payload);
    PaymentResultV1 result = mapper.treeToValue(node.get("result"), PaymentResultV1.class);
    String status = node.path("applicationStatus").asText();
    if (!status.startsWith("APPLIED")) return;
    switch (result.resultType()) {
      case PAYMENT_SUCCEEDED -> applyPaymentSuccess(eventId, result);
      case REFUND_SUCCEEDED -> applyRefundSuccess(eventId, result);
      case REFUND_FAILED -> applyRefundFailure(eventId, result);
      default -> {
        // Payment failure and close facts do not move an order by themselves.
      }
    }
  }

  private void applyPaymentSuccess(String eventId, PaymentResultV1 result) {
    OrderView order = orders.order(result.orderId());
    String from = order.status();
    if ("PENDING_PAYMENT".equals(from)) {
      orders.status(eventId + ":order:paid", order.id(), from, "PAID");
    } else if ("CANCELLING".equals(from)) {
      orders.restore(eventId + ":order:payment-race", order.id(), false);
    } else if ("CANCELLED".equals(from)) {
      orders.restore(eventId + ":order:late-payment", order.id(), true);
      OrderView restored = orders.order(order.id());
      orders.event(eventId + ":order:event", order.id(), order.customerId(),
        "LATE_PAYMENT_AFTER_CANCELLATION", "CANCELLED", "PAID",
        "Late successful payment queued an automatic refund");
      refunds.requestAutomatic(restored, result.resultId(), result.traceparent());
      return;
    }
    commitInventory(eventId, result, orders.order(order.id()));
    orders.event(eventId + ":order:event", order.id(), order.customerId(),
      "PAYMENT_SUCCEEDED", from, "PAID", "Payment result applied by payment-orchestration");
  }

  private void commitInventory(String eventId, PaymentResultV1 result, OrderView order) {
    String inventoryStatus = order.inventoryStatus();
    if ("RESERVED".equals(inventoryStatus)) {
      orders.startInventoryCommit(eventId + ":order:inventory-wait", order.id());
      inventoryStatus = orders.order(order.id()).inventoryStatus();
    }
    if (!List.of("WAITING_COMMIT", "COMMITTED").contains(inventoryStatus)) {
      throw new IllegalStateException("Order inventory cannot be committed from "
        + inventoryStatus);
    }
    inventory.commit(eventId + ":inventory:commit", order.id(), result.resultId(),
      result.traceparent());
    orders.inventory(eventId + ":order:inventory-committed", order.id(), "WAITING_COMMIT",
      "COMMITTED");
  }

  private void applyRefundRequested(String eventId, JsonNode payload) {
    Long orderId = payload.get("orderId").asLong();
    String refundNumber = payload.get("refundNumber").asText();
    String traceparent = payload.get("traceparent").asText();
    if (payload.path("lineRefund").asBoolean(false)) {
      enqueueRefundCommand(refundNumber, orderId, eventId, traceparent);
      return;
    }
    var admission = orders.startRefund(eventId + ":order:refund-admission", orderId, refundNumber);
    if (!admission.accepted()) {
      store.rejectRefundAdmission(refundNumber);
      return;
    }
    store.recordRefundPriorOrderStatus(refundNumber, admission.previousStatus());
    enqueueRefundCommand(refundNumber, orderId, eventId, traceparent);
  }

  private void enqueueRefundCommand(String refundNumber, Long orderId, String eventId,
                                    String traceparent) {
    String processId = "refund-" + refundNumber;
    String commandId = UUID.nameUUIDFromBytes((refundNumber + ":channel")
      .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    Map<String,Object> refund = store.findRefundChannelData(refundNumber);
    String commandPayload = mapper.writeValueAsString(Map.of(
      "refundNumber", refundNumber, "channel", refund.get("channel"),
      "channelTransactionNo", refund.get("channel_transaction_no"),
      "amount", refund.get("amount"), "currency", refund.get("currency"),
      "reasonCode", refund.get("reason_code")));
    String fingerprint = vip.fairy.commerceflow.payment.application.contract.v1
      .PaymentContractSemantics.sha256(commandPayload);
    store.insertRefundProcess(processId, orderId, commandId, fingerprint, "refund-" + orderId, eventId, traceparent);
    store.insertRefundCommand(commandId, processId, orderId, fingerprint, commandPayload, "refund-" + orderId,
        eventId, traceparent);
    store.markRefundProcessing(refundNumber);
  }

  private void dispatchRefund(String commandId) {
    if (store.claimRefundCommand(commandId) != 1) return;
    try {
      String payload = store.refundCommandPayload(commandId);
      JsonNode node = mapper.readTree(payload);
      ChannelGatewayClient.RefundResult result = gateway.refund(node.get("refundNumber").asText(),
        vip.fairy.commerceflow.payment.application.contract.v1.PaymentChannelV1.valueOf(
          node.get("channel").asText()), node.get("channelTransactionNo").asText(),
        node.get("amount").decimalValue(), node.get("currency").asText(),
        node.get("reasonCode").asText());
      store.assignChannelRefundNo(result.channelRefundNo(), result.merchantRefundNo());
      store.acknowledgeRefundCommand(commandId);
    } catch (RuntimeException exception) {
      store.retryRefundCommand(error(exception), commandId);
    }
  }

  private void applyRefundSuccess(String eventId, PaymentResultV1 result) {
    var refund = store.findRefundPreviousState(result.refundNumber());
    if (refund != null && refund.get("after_sale_id") != null) return;
    OrderView order = orders.order(result.orderId());
    orders.status(eventId + ":order:refunded", order.id(), "REFUNDING", "REFUNDED");
    if ("COMMITTED".equals(order.inventoryStatus())) {
      orders.inventory(eventId + ":order:refund-wait", order.id(), "COMMITTED", "REFUND_PENDING");
    }
    inventory.refund(eventId + ":inventory:refund", order.id(), result.resultId(),
      result.traceparent());
    orders.inventory(eventId + ":order:inventory-refunded", order.id(), "REFUND_PENDING",
      "REFUNDED");
    store.markRefundInventoryRestored(result.refundNumber());
    orders.event(eventId + ":order:event", order.id(), order.customerId(), "ORDER_REFUNDED",
      "REFUNDING", "REFUNDED", "Refund " + result.refundNumber() + " succeeded");
  }

  private void applyRefundFailure(String eventId, PaymentResultV1 result) {
    Map<String,Object> refund = store.findRefundPreviousState(result.refundNumber());
    if (refund != null && refund.get("after_sale_id") != null) return;
    String target = String.valueOf(refund.get("order_status_before_refund"));
    orders.status(eventId + ":order:refund-failed", result.orderId(), "REFUNDING", target);
    orders.event(eventId + ":order:event", result.orderId(),
      ((Number) refund.get("customer_id")).longValue(), "ORDER_REFUND_FAILED", "REFUNDING", target,
      "Refund " + result.refundNumber() + " failed");
  }

  private boolean enabled() {
    return properties.acceptingTraffic() && properties.effects() != null
      && properties.effects().enabled() && properties.shadow() != null
      && !properties.shadow().enabled();
  }

  private String error(RuntimeException exception) {
    String value = exception.getClass().getSimpleName() + ": " + exception.getMessage();
    return value.substring(0, Math.min(value.length(), 500));
  }
}
