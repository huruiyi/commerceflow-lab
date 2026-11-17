package vip.fairy.commerceflow.payment.application;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultTypeV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultV1;
import vip.fairy.commerceflow.payment.infrastructure.ChannelGatewayClient;
import vip.fairy.commerceflow.payment.infrastructure.PaymentShadowProperties;
import vip.fairy.commerceflow.payment.infrastructure.RemoteInventoryEffectClient;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient.OrderView;

@Service
public class PaymentEffectDispatcher {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final PaymentShadowProperties properties;
  private final RemoteOrderEffectClient orders;
  private final RemoteInventoryEffectClient inventory;
  private final RefundApplicationService refunds;
  private final ChannelGatewayClient gateway;

  public PaymentEffectDispatcher(JdbcTemplate jdbc, ObjectMapper mapper,
                                 PaymentShadowProperties properties,
                                 RemoteOrderEffectClient orders,
                                 RemoteInventoryEffectClient inventory,
                                 RefundApplicationService refunds,
                                 ChannelGatewayClient gateway) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.properties = properties;
    this.orders = orders;
    this.inventory = inventory;
    this.refunds = refunds;
    this.gateway = gateway;
  }

  @Scheduled(fixedDelayString = "${commerceflow.payment.effects.dispatch-delay:1s}")
  public void dispatchEffects() {
    if (!enabled()) return;
    List<Long> ids = jdbc.query("SELECT id FROM payment_outbox WHERE "
        + "((status IN ('NEW','RETRY') AND next_attempt_at<=CURRENT_TIMESTAMP(6)) "
        + "OR (status='PROCESSING' AND next_attempt_at<CURRENT_TIMESTAMP(6))) ORDER BY id LIMIT ?",
      (result, row) -> result.getLong(1), properties.effects().batchSize());
    for (Long id : ids) dispatchEffect(id);
  }

  @Scheduled(fixedDelayString = "${commerceflow.payment.effects.command-delay:1s}")
  public void dispatchRefundCommands() {
    if (!enabled()) return;
    List<String> ids = jdbc.query("SELECT command_id FROM payment_command_outbox "
        + "WHERE command_type='CREATE_REFUND' AND ((status IN ('NEW','RETRY') "
        + "AND next_attempt_at<=CURRENT_TIMESTAMP(6)) OR (status='IN_FLIGHT' "
        + "AND next_attempt_at<CURRENT_TIMESTAMP(6))) ORDER BY created_at LIMIT ?",
      (result, row) -> result.getString(1), properties.effects().batchSize());
    for (String id : ids) dispatchRefund(id);
  }

  private void dispatchEffect(Long id) {
    int claimed = jdbc.update("UPDATE payment_outbox SET status='PROCESSING',attempts=attempts+1,"
        + "next_attempt_at=CURRENT_TIMESTAMP(6) + INTERVAL 30 SECOND,last_error=NULL WHERE id=? AND "
        + "(status IN ('NEW','RETRY') OR (status='PROCESSING' AND next_attempt_at<CURRENT_TIMESTAMP(6)))",
      id);
    if (claimed != 1) return;
    try {
      Map<String,Object> row = jdbc.queryForMap("SELECT event_id,event_type,payload FROM "
        + "payment_outbox WHERE id=?", id);
      apply(String.valueOf(row.get("event_id")), String.valueOf(row.get("event_type")),
        String.valueOf(row.get("payload")));
      jdbc.update("UPDATE payment_outbox SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(6),"
        + "last_error=NULL WHERE id=?", id);
    } catch (RuntimeException exception) {
      jdbc.update("UPDATE payment_outbox SET status='RETRY',next_attempt_at=CURRENT_TIMESTAMP(6) "
          + "+ INTERVAL LEAST(attempts,30) SECOND,last_error=? WHERE id=?",
        error(exception), id);
    }
  }

  void apply(String eventId, String eventType, String payload) {
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
    String state = order.inventoryStatus();
    if ("RESERVED".equals(state) || "WAITING_RESERVATION".equals(state)) {
      orders.inventory(eventId + ":order:inventory-wait", order.id(), state, "WAITING_COMMIT");
    }
    OrderView waiting = orders.order(order.id());
    if (!List.of("WAITING_COMMIT", "COMMITTED").contains(waiting.inventoryStatus())) {
      throw new IllegalStateException("Order inventory cannot be committed from "
        + waiting.inventoryStatus());
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
    OrderView order = orders.order(orderId);
    if (List.of("PAID", "CONFIRMED").contains(order.status())) {
      orders.status(eventId + ":order:refunding", orderId, order.status(), "REFUNDING");
      orders.event(eventId + ":order:event", orderId, order.customerId(), "ORDER_REFUND_REQUESTED",
        order.status(), "REFUNDING", "Refund " + refundNumber + " requested");
    } else if (!"REFUNDING".equals(order.status())) {
      throw new IllegalStateException("Order cannot start refund from " + order.status());
    }
    enqueueRefundCommand(refundNumber, orderId, eventId, traceparent);
  }

  private void enqueueRefundCommand(String refundNumber, Long orderId, String eventId,
                                    String traceparent) {
    String processId = "refund-" + refundNumber;
    String commandId = UUID.nameUUIDFromBytes((refundNumber + ":channel")
      .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    Map<String,Object> refund = jdbc.queryForMap("SELECT r.*,p.channel,p.channel_transaction_no "
      + "FROM refund_records r JOIN payment_records p ON p.id=r.payment_id "
      + "WHERE r.refund_number=?", refundNumber);
    String commandPayload = mapper.writeValueAsString(Map.of(
      "refundNumber", refundNumber, "channel", refund.get("channel"),
      "channelTransactionNo", refund.get("channel_transaction_no"),
      "amount", refund.get("amount"), "currency", refund.get("currency"),
      "reasonCode", refund.get("reason_code")));
    String fingerprint = vip.fairy.commerceflow.payment.application.contract.v1
      .PaymentContractSemantics.sha256(commandPayload);
    jdbc.update("INSERT IGNORE INTO payment_process_managers(process_id,order_id,process_type,status,"
        + "command_id,command_fingerprint,correlation_id,causation_id,traceparent,result_deadline_at,"
        + "next_check_at,version,created_at,updated_at) VALUES (?,?,'REFUND_CREATE','WAITING_RESULT',"
        + "?,?,?,?,?,CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE,CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE,"
        + "0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
      processId, orderId, commandId, fingerprint, "refund-" + orderId, eventId, traceparent);
    jdbc.update("INSERT IGNORE INTO payment_command_outbox(command_id,process_id,order_id,command_type,"
        + "payload_fingerprint,payload,status,attempts,next_attempt_at,result_deadline_at,correlation_id,"
        + "causation_id,traceparent,created_at) VALUES (?,?,?,'CREATE_REFUND',?,?,'NEW',0,"
        + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)+INTERVAL 5 MINUTE,?,?,?,CURRENT_TIMESTAMP(6))",
      commandId, processId, orderId, fingerprint, commandPayload, "refund-" + orderId, eventId,
      traceparent);
    jdbc.update("UPDATE refund_records SET status='PROCESSING',updated_at=CURRENT_TIMESTAMP(6) "
      + "WHERE refund_number=? AND status='REQUESTED'", refundNumber);
  }

  private void dispatchRefund(String commandId) {
    if (jdbc.update("UPDATE payment_command_outbox SET status='IN_FLIGHT',attempts=attempts+1,"
        + "next_attempt_at=CURRENT_TIMESTAMP(6)+INTERVAL 30 SECOND WHERE command_id=? "
      + "AND (status IN ('NEW','RETRY') OR (status='IN_FLIGHT' "
      + "AND next_attempt_at<CURRENT_TIMESTAMP(6)))", commandId) != 1) return;
    try {
      String payload = jdbc.queryForObject("SELECT payload FROM payment_command_outbox "
        + "WHERE command_id=?", String.class, commandId);
      JsonNode node = mapper.readTree(payload);
      ChannelGatewayClient.RefundResult result = gateway.refund(node.get("refundNumber").asText(),
        vip.fairy.commerceflow.payment.application.contract.v1.PaymentChannelV1.valueOf(
          node.get("channel").asText()), node.get("channelTransactionNo").asText(),
        node.get("amount").decimalValue(), node.get("currency").asText(),
        node.get("reasonCode").asText());
      jdbc.update("UPDATE refund_records SET channel_refund_no=?,updated_at=CURRENT_TIMESTAMP(6) "
          + "WHERE refund_number=?", result.channelRefundNo(), result.merchantRefundNo());
      jdbc.update("UPDATE payment_command_outbox SET status='ACKNOWLEDGED',"
        + "acknowledged_at=CURRENT_TIMESTAMP(6),last_error=NULL WHERE command_id=?", commandId);
    } catch (RuntimeException exception) {
      jdbc.update("UPDATE payment_command_outbox SET status='RETRY',"
        + "next_attempt_at=CURRENT_TIMESTAMP(6)+INTERVAL LEAST(attempts,30) SECOND,last_error=? "
        + "WHERE command_id=?", error(exception), commandId);
    }
  }

  private void applyRefundSuccess(String eventId, PaymentResultV1 result) {
    OrderView order = orders.order(result.orderId());
    orders.status(eventId + ":order:refunded", order.id(), "REFUNDING", "REFUNDED");
    if ("COMMITTED".equals(order.inventoryStatus())) {
      orders.inventory(eventId + ":order:refund-wait", order.id(), "COMMITTED", "REFUND_PENDING");
    }
    inventory.refund(eventId + ":inventory:refund", order.id(), result.resultId(),
      result.traceparent());
    orders.inventory(eventId + ":order:inventory-refunded", order.id(), "REFUND_PENDING",
      "REFUNDED");
    jdbc.update("UPDATE refund_records SET inventory_status='REFUNDED',"
      + "updated_at=CURRENT_TIMESTAMP(6) WHERE refund_number=? AND inventory_status<>'REFUNDED'",
      result.refundNumber());
    orders.event(eventId + ":order:event", order.id(), order.customerId(), "ORDER_REFUNDED",
      "REFUNDING", "REFUNDED", "Refund " + result.refundNumber() + " succeeded");
  }

  private void applyRefundFailure(String eventId, PaymentResultV1 result) {
    Map<String,Object> refund = jdbc.queryForMap("SELECT order_status_before_refund,customer_id "
      + "FROM refund_records WHERE refund_number=?", result.refundNumber());
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
