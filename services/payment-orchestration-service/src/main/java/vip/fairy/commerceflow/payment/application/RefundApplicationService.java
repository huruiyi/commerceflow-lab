package vip.fairy.commerceflow.payment.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.application.RemotePaymentProtocolService.ProtocolConflictException;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentContractSemantics;
import vip.fairy.commerceflow.payment.application.contract.v1.RefundCreateCommandV1;
import vip.fairy.commerceflow.payment.infrastructure.RemoteOrderEffectClient.OrderView;
import vip.fairy.commerceflow.payment.infrastructure.persistence.RefundRequestMapper;

@Service
public class RefundApplicationService {

  private static final String AUTO_REASON = "Automatic refund for late payment after cancellation";

  private final RefundRequestMapper store;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public RefundApplicationService(RefundRequestMapper store, ObjectMapper mapper,
                                  PlatformTransactionManager manager) {
    this.store = store;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
  }

  public RefundReceipt request(RefundCreateCommandV1 command, OrderView order) {
    command.validate();
    if (!command.orderId().equals(order.id()) || !command.customerId().equals(order.customerId())) {
      throw new ProtocolConflictException("Refund command does not match the order owner");
    }
    List<Map<String,Object>> existing = store.latestRefund(command.orderId());
    if (!existing.isEmpty()) {
      Map<String,Object> row = existing.getFirst();
      if (!command.idempotencyKey().equals(row.get("idempotency_key"))) {
        throw new ProtocolConflictException("Payment already has a different refund request");
      }
      return receipt(row);
    }
    if (!List.of("PAID", "CONFIRMED").contains(order.status())) {
      throw new ProtocolConflictException("Only paid or confirmed orders can be refunded");
    }
    return transactions.execute(status -> prepare(command, order.status()));
  }

  public RefundReceipt requestAutomatic(OrderView order, String notificationId,
                                        String traceparent) {
    RefundCreateCommandV1 command = new RefundCreateCommandV1(1,
      stable(notificationId + ":automatic-refund"), order.id(), order.customerId(),
      "late-payment:" + notificationId, "CUSTOMER_CANCELLED", AUTO_REASON,
      "refund-" + order.id(), notificationId, traceparent);
    return request(command, order);
  }

  private RefundReceipt prepare(RefundCreateCommandV1 command, String orderStatus) {
    String fingerprint = PaymentContractSemantics.sha256(mapper.writeValueAsString(command));
    List<Map<String,Object>> replay = store.lockCommandInbox(command.commandId());
    if (!replay.isEmpty()) {
      if (!fingerprint.equals(replay.getFirst().get("payload_fingerprint"))) {
        throw new ProtocolConflictException("Refund command id was reused with different payload");
      }
      return mapper.readValue(String.valueOf(replay.getFirst().get("response_payload")),
        RefundReceipt.class);
    }
    Map<String,Object> payment = successfulPayment(command.orderId());
    List<Map<String,Object>> existing = store.lockRefundByPayment(payment.get("id"));
    if (!existing.isEmpty()) {
      Map<String,Object> row = existing.getFirst();
      if (!command.idempotencyKey().equals(row.get("idempotency_key"))) {
        throw new ProtocolConflictException("Payment already has a different refund request");
      }
      return receipt(row);
    }
    store.insertRefundInbox(command.commandId(), fingerprint);
    String refundNumber = "REF-RMT-" + compact(command.commandId(), 16);
    String requestFingerprint = PaymentContractSemantics.sha256(command.customerId() + "|"
      + command.orderId() + "|" + payment.get("id") + "|" + payment.get("amount") + "|"
      + payment.get("currency") + "|" + command.reasonCode() + "|" + detail(command));
    store.insertRefund(refundNumber, command.orderId(), payment.get("id"), command.customerId(),
        command.idempotencyKey(), requestFingerprint, payment.get("amount"), payment.get("currency"),
        command.reasonCode(), detail(command), orderStatus);
    long refundId = store.findRefundId(refundNumber);
    store.insertRefundRequestedEvent(refundId, UUID.randomUUID().toString(), "REFUND_REQUESTED", command.reasonCode());
    RefundReceipt response = new RefundReceipt(refundNumber, command.orderId(), "REQUESTED",
      (BigDecimal) payment.get("amount"), String.valueOf(payment.get("currency")), Instant.now());
    store.finishRefundInbox(mapper.writeValueAsString(response), command.commandId());
    store.insertRefundRequestedOutbox(command.commandId(), refundNumber,
      mapper.writeValueAsString(Map.of("refundNumber", refundNumber, "orderId", command.orderId(),
        "customerId", command.customerId(), "traceparent", command.traceparent())));
    return response;
  }

  private Map<String,Object> successfulPayment(Long orderId) {
    List<Map<String,Object>> rows = store.lockSuccessfulPayment(orderId);
    if (rows.isEmpty()) throw new ProtocolConflictException("Successful payment was not found");
    return rows.getFirst();
  }

  private RefundReceipt receipt(Map<String,Object> row) {
    return new RefundReceipt(String.valueOf(row.get("refund_number")),
      ((Number) row.get("order_id")).longValue(), String.valueOf(row.get("status")),
      (BigDecimal) row.get("amount"), String.valueOf(row.get("currency")),
      ((java.sql.Timestamp) row.get("requested_at")).toInstant());
  }

  private String detail(RefundCreateCommandV1 command) {
    return command.reasonDetail() == null ? "" : command.reasonDetail().trim();
  }

  private String stable(String value) {
    return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private String compact(String value, int length) {
    String compact = value.replace("-", "").toUpperCase();
    return compact.substring(0, Math.min(length, compact.length()));
  }

  public record RefundReceipt(String refundNumber, Long orderId, String status,
                              BigDecimal amount, String currency, Instant requestedAt) {
  }
}
