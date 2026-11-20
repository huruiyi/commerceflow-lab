package vip.fairy.commerceflow.payment.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentChannelV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentCloseCommandV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentCloseOutcomeV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentCloseResultV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentContractSemantics;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentCreateCommandV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentQueryV1;
import vip.fairy.commerceflow.payment.application.contract.v1.RefundQueryV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultOutcomeV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultTypeV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentResultV1;
import vip.fairy.commerceflow.payment.application.contract.v1.PaymentSnapshotV1;
import vip.fairy.commerceflow.payment.infrastructure.ChannelGatewayClient;
import vip.fairy.commerceflow.payment.infrastructure.persistence.PaymentProtocolMapper;

@Service
public class RemotePaymentProtocolService {

  private final PaymentProtocolMapper store;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final ChannelGatewayClient gateway;
  private final PaymentReportOwnership reportOwnership;

  public RemotePaymentProtocolService(PaymentProtocolMapper store, ObjectMapper mapper,
                                      PlatformTransactionManager manager,
                                      ChannelGatewayClient gateway, PaymentReportOwnership reportOwnership) {
    this.store = store;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
    this.gateway = gateway;
    this.reportOwnership = reportOwnership;
  }

  public PaymentSnapshotV1 create(PaymentCreateCommandV1 command) {
    command.validate();
    String fingerprint = PaymentContractSemantics.fingerprint(command);
    return transactions.execute(status -> {
      PaymentSnapshotV1 replay = replay(command.commandId(), fingerprint, PaymentSnapshotV1.class);
      if (replay != null) {
        reportOwnership.rememberVerified(command.orderId(), command.orderMerchantId());
        return replay;
      }
      insertMessage(command.commandId(), "CREATE", fingerprint);
      List<Map<String,Object>> existing = paymentByIdempotency(command.idempotencyKey(), true);
      if (!existing.isEmpty()) {
        if (!PaymentContractSemantics.requestFingerprint(command)
          .equals(existing.getFirst().get("request_fingerprint"))) {
          throw new ProtocolConflictException(
            "Idempotency key was reused with different payment parameters");
        }
        reportOwnership.rememberVerified(command.orderId(), command.orderMerchantId());
        return finish(command.commandId(), fingerprint, "IDEMPOTENT_REPLAY",
          snapshot(existing.getFirst()), null);
      }
      Map<String,Object> merchant = merchant(command.channel());
      String paymentNumber = "PAY-RMT-" + compact(command.commandId(), 20);
      ChannelGatewayClient.Result remote = gateway.create(paymentNumber, command.channel(),
        "Order " + command.orderNumber(), command.amount(), command.currency(),
        command.simulateFailure());
      String requestFingerprint = PaymentContractSemantics.requestFingerprint(command);
      store.ensureIdempotencyLock(command.idempotencyKey());
      store.insertPayment(command.orderId(), paymentNumber, command.idempotencyKey(), command.amount(),
        merchant.get("id"), command.channel().name(), command.currency(), requestFingerprint,
        remote.channelTransactionNo(), Timestamp.from(remote.expiresInstant()), remote.checkoutUrl());
      Map<String,Object> payment = paymentByIdempotency(command.idempotencyKey(), true).getFirst();
      long paymentId = number(payment.get("id"));
      store.insertPaymentEvent(paymentId, UUID.randomUUID().toString(),
        "PAYMENT_PENDING", "CREATED", "PENDING", "Remote Payment V1 accepted the command");
      String processId = "payment-" + command.commandId();
      store.insertCreateProcess(processId, command.orderId(), command.commandId(), fingerprint,
        command.correlationId(), command.causationId(), command.traceparent());
      String childCommandId = UUID.nameUUIDFromBytes(
        (command.commandId() + ":channel-result").getBytes(StandardCharsets.UTF_8)).toString();
      store.insertCreateCommand(childCommandId, processId, command.orderId(), fingerprint, json(command),
        command.correlationId(), command.commandId(), command.traceparent());
      reportOwnership.rememberVerified(command.orderId(), command.orderMerchantId());
      return finish(command.commandId(), fingerprint, "WAITING_RESULT", snapshot(payment), null);
    });
  }

  public Optional<PaymentSnapshotV1> latest(PaymentQueryV1 query) {
    query.validate();
    List<Map<String,Object>> rows = store.latestPayment(query.orderId());
    return rows.isEmpty() ? Optional.empty() : Optional.of(snapshot(rows.getFirst()));
  }

  public Optional<RefundSnapshot> refund(PaymentQueryV1 query) {
    query.validate();
    List<Map<String,Object>> rows = store.latestRefund(query.orderId());
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(refundSnapshot(rows.getFirst()));
  }

  public Optional<RefundSnapshot> refund(RefundQueryV1 query) {
    query.validate();
    List<Map<String,Object>> rows = store.refundByNumber(query.refundNumber());
    return rows.isEmpty() ? Optional.empty() : Optional.of(refundSnapshot(rows.getFirst()));
  }

  private RefundSnapshot refundSnapshot(Map<String,Object> row) {
    List<RefundEvent> events = store.refundEvents(number(row.get("id")));
    return new RefundSnapshot(1, text(row, "refund_number"), number(row.get("order_id")),
      text(row, "status"), text(row, "inventory_status"), decimal(row, "amount"),
      text(row, "currency"), text(row, "reason_code"), text(row, "reason_detail"),
      nullableText(row, "channel_refund_no"), text(row, "failure_reason"),
      instant(row, "requested_at"), instant(row, "succeeded_at"), instant(row, "failed_at"), events);
  }

  public PaymentCloseResultV1 close(PaymentCloseCommandV1 command) {
    command.validate();
    String fingerprint = PaymentContractSemantics.fingerprint(command);
    return transactions.execute(status -> {
      PaymentCloseResultV1 replay = replay(command.commandId(), fingerprint,
        PaymentCloseResultV1.class);
      if (replay != null) return replay;
      insertMessage(command.commandId(), "CLOSE", fingerprint);
      List<Map<String,Object>> payments = store.lockOrderPayments(command.orderId());
      boolean succeeded = payments.stream().anyMatch(row -> "SUCCESS".equals(row.get("status")));
      int closed = 0;
      if (!succeeded) {
        for (Map<String,Object> payment : payments) {
          if (!"PENDING".equals(payment.get("status"))) continue;
          gateway.close(PaymentChannelV1.valueOf(text(payment, "channel")),
            text(payment, "channel_transaction_no"));
          int changed = store.closePendingPayment(command.reason(), number(payment.get("id")));
          if (changed == 1) {
            closed++;
            store.insertPaymentEvent(number(payment.get("id")),
              UUID.randomUUID().toString(), "PAYMENT_CLOSED_BY_ORDER", "PENDING", "CLOSED",
              command.reason());
          }
        }
      }
      boolean alreadyClosed = !payments.isEmpty()
        && payments.stream().allMatch(payment -> "CLOSED".equals(payment.get("status")));
      if (!succeeded && (closed > 0 || alreadyClosed)) {
        completeClosedPaymentProcesses(command.orderId());
      }
      PaymentCloseOutcomeV1 outcome = succeeded ? PaymentCloseOutcomeV1.PAYMENT_ALREADY_SUCCEEDED
        : PaymentCloseOutcomeV1.CLOSED;
      PaymentCloseResultV1 result = new PaymentCloseResultV1(1,
        UUID.nameUUIDFromBytes(command.commandId().getBytes(StandardCharsets.UTF_8)).toString(),
        outcome, Instant.now(), "payment-orchestration-service", command.orderId(), closed,
        succeeded ? "A successful payment already exists" : "Pending payments were closed",
        command.correlationId(), command.commandId(), command.traceparent());
      return finish(command.commandId(), fingerprint, outcome.name(), result, null);
    });
  }

  private void completeClosedPaymentProcesses(Long orderId) {
    store.acknowledgeCreateCommands(orderId);
    store.completeCreateProcesses(orderId);
  }

  public ContractReceipt result(PaymentResultV1 result) {
    result.validate();
    return transactions.execute(status -> applyResult(result));
  }

  public CallbackReceipt paymentCallback(PaymentNotice notice, String rawPayload) {
    return transactions.execute(status -> {
      String digest = PaymentContractSemantics.sha256(rawPayload);
      List<Map<String,Object>> duplicate = store.findPaymentNotification(notice.channel(), notice.notificationId());
      if (!duplicate.isEmpty()) {
        if (!digest.equals(duplicate.getFirst().get("payload_digest"))) {
          throw new ProtocolConflictException(
            "Payment notification id was reused with a different payload");
        }
        return new CallbackReceipt("ACKNOWLEDGED");
      }
      List<Map<String,Object>> rows = store.lockByChannelTransaction(notice.channelTransactionNo());
      if (rows.isEmpty()) throw new ProtocolNotFoundException("Payment was not found");
      Map<String,Object> payment = rows.getFirst();
      if (decimal(payment, "amount").compareTo(notice.amount()) != 0
          || !text(payment, "currency").equals(notice.currency())
          || !text(payment, "channel").equals(notice.channel())) {
        insertPaymentNotification(payment, notice, digest, "REJECTED",
          "Channel, amount or currency mismatch");
        return new CallbackReceipt("REJECTED");
      }
      PaymentResultTypeV1 type = switch (notice.status()) {
        case "SUCCESS" -> PaymentResultTypeV1.PAYMENT_SUCCEEDED;
        case "FAILED" -> PaymentResultTypeV1.PAYMENT_FAILED;
        case "CLOSED", "EXPIRED" -> PaymentResultTypeV1.PAYMENT_CLOSED;
        default -> null;
      };
      if (type == null) {
        insertPaymentNotification(payment, notice, digest, "IGNORED", "Non-terminal callback");
        return new CallbackReceipt("IGNORED");
      }
      PaymentResultV1 result = new PaymentResultV1(1, notice.notificationId(), type,
        PaymentResultOutcomeV1.SUCCEEDED, Instant.now(), "payment-simulator-service",
        number(payment.get("order_id")), text(payment, "payment_number"), null, notice.status(),
        null, notice.failureReason() == null || notice.failureReason().isBlank()
          ? "Signed channel callback" : notice.failureReason(),
        "payment-" + payment.get("order_id"), notice.notificationId(), notice.traceparent());
      ContractReceipt receipt = applyResult(result);
      insertPaymentNotification(payment, notice, digest,
        receipt.status().startsWith("APPLIED") ? "PROCESSED" : "DUPLICATE_OR_FINAL", "");
      return new CallbackReceipt(receipt.status());
    });
  }

  public CallbackReceipt refundCallback(RefundNotice notice, String rawPayload) {
    return transactions.execute(status -> {
      String digest = PaymentContractSemantics.sha256(rawPayload);
      List<Map<String,Object>> duplicate = store.findRefundNotification(notice.channel(), notice.notificationId());
      if (!duplicate.isEmpty()) {
        if (!digest.equals(duplicate.getFirst().get("payload_digest"))) {
          throw new ProtocolConflictException(
            "Refund notification id was reused with a different payload");
        }
        return new CallbackReceipt("ACKNOWLEDGED");
      }
      List<Map<String,Object>> rows = store.lockRefundForChannel(notice.merchantRefundNo());
      if (rows.isEmpty()) throw new ProtocolNotFoundException("Refund was not found");
      Map<String,Object> refund = rows.getFirst();
      if (decimal(refund, "amount").compareTo(notice.amount()) != 0
          || !text(refund, "currency").equals(notice.currency())
          || !text(refund, "payment_channel").equals(notice.channel())) {
        insertRefundNotification(refund, notice, digest, "REJECTED",
          "Channel, amount or currency mismatch");
        return new CallbackReceipt("REJECTED");
      }
      PaymentResultTypeV1 type = "SUCCEEDED".equals(notice.status())
        ? PaymentResultTypeV1.REFUND_SUCCEEDED : PaymentResultTypeV1.REFUND_FAILED;
      PaymentResultV1 result = new PaymentResultV1(1, notice.notificationId(), type,
        PaymentResultOutcomeV1.SUCCEEDED, Instant.now(), "payment-simulator-service",
        number(refund.get("order_id")), text(refund, "payment_number"),
        text(refund, "refund_number"), notice.status(), null,
        notice.failureReason() == null || notice.failureReason().isBlank()
          ? "Signed refund callback" : notice.failureReason(),
        "refund-" + refund.get("order_id"), notice.notificationId(), notice.traceparent());
      ContractReceipt receipt = applyResult(result);
      insertRefundNotification(refund, notice, digest,
        receipt.status().startsWith("APPLIED") ? "PROCESSED" : "DUPLICATE_OR_FINAL", "");
      return new CallbackReceipt(receipt.status());
    });
  }

  public List<Map<String,Object>> recoverable() {
    return store.recoverableProcesses();
  }

  public ContractReceipt recover(String processId, RecoveryRequest request) {
    if (request.operator() == null || request.operator().isBlank()
        || request.reason() == null || request.reason().isBlank()) {
      throw new IllegalArgumentException("Recovery operator and reason are required");
    }
    return transactions.execute(status -> {
      int changed = store.retryProcess(request.operator(), request.reason(), processId);
      if (changed != 1) throw new ProtocolConflictException("Payment process is not recoverable");
      store.retryProcessCommands(processId);
      return new ContractReceipt(processId, "", "RETRY", request.traceparent());
    });
  }

  @Scheduled(fixedDelayString = "${commerceflow.payment.protocol.timeout-scan-delay:5s}")
  public void markUnknownResults() {
    store.markUnknownResults();
  }

  private ContractReceipt applyResult(PaymentResultV1 result) {
    String fingerprint = PaymentContractSemantics.fingerprint(result);
    List<Map<String,Object>> replay = store.findResult(result.resultId());
    if (!replay.isEmpty()) {
      if (!fingerprint.equals(replay.getFirst().get("payload_fingerprint"))) {
        throw new ProtocolConflictException("Result id was reused with a different payload");
      }
      return receipt(result.resultId(), fingerprint,
        "DUPLICATE_" + replay.getFirst().get("processing_status"), result.traceparent());
    }
    List<Map<String,Object>> payments = store.lockPaymentByNumber(result.paymentNumber());
    if (payments.isEmpty()) {
      insertResult(result, fingerprint, null, "IGNORED", "Payment was not found");
      return receipt(result.resultId(), fingerprint, "UNMATCHED", result.traceparent());
    }
    Map<String,Object> payment = payments.getFirst();
    String processId = result.refundNumber() == null ? processId(result.orderId())
      : "refund-" + result.refundNumber();
    if (result.outcome() != PaymentResultOutcomeV1.SUCCEEDED) {
      String processStatus = result.outcome() == PaymentResultOutcomeV1.RECONCILIATION_REQUIRED
        ? "RECONCILIATION_REQUIRED" : "COMPENSATION_REQUIRED";
      insertResult(result, fingerprint, processId, "IGNORED", result.errorCode());
      if (processId != null) {
        store.recordProcessFailure(processStatus, result.errorCode() + ": " + result.message(), processId);
      }
      appendOutbox(result, processStatus);
      return receipt(result.resultId(), fingerprint, processStatus, result.traceparent());
    }
    String applied = switch (result.resultType()) {
      case PAYMENT_CREATED -> "IGNORED_CREATED_ACK";
      case PAYMENT_SUCCEEDED -> applyPaymentSuccess(payment, result);
      case PAYMENT_FAILED -> applyPaymentTerminal(payment, result, "FAILED");
      case PAYMENT_CLOSED -> applyPaymentTerminal(payment, result, "CLOSED");
      case REFUND_SUCCEEDED -> applyRefund(payment, result, true);
      case REFUND_FAILED -> applyRefund(payment, result, false);
    };
    String processing = applied.startsWith("APPLIED") ? "APPLIED" : "IGNORED";
    insertResult(result, fingerprint, processId, processing, null);
    if (applied.startsWith("APPLIED") && processId != null) {
      store.completeProcess(processId);
      store.acknowledgeProcessCommands(processId);
    }
    appendOutbox(result, applied);
    return receipt(result.resultId(), fingerprint, applied, result.traceparent());
  }

  private String applyPaymentSuccess(Map<String,Object> payment, PaymentResultV1 result) {
    String current = text(payment, "status");
    if ("SUCCESS".equals(current)) return "IGNORED_ALREADY_SUCCESSFUL";
    store.markPaymentSucceeded(number(payment.get("id")));
    store.insertPaymentEvent(number(payment.get("id")), result.resultId(),
      "CALLBACK_SUCCESS", current, "SUCCESS", result.message());
    return "APPLIED_PAYMENT_SUCCEEDED";
  }

  private String applyPaymentTerminal(Map<String,Object> payment, PaymentResultV1 result,
                                      String target) {
    String current = text(payment, "status");
    if ("SUCCESS".equals(current)) return "IGNORED_PAYMENT_ALREADY_SUCCEEDED";
    if (target.equals(current)) return "IGNORED_ALREADY_" + target;
    store.markPaymentTerminal(target, result.message(), number(payment.get("id")));
    store.insertPaymentEvent(number(payment.get("id")), result.resultId(),
      "CALLBACK_" + target, current, target, result.message());
    return "APPLIED_PAYMENT_" + target;
  }

  private String applyRefund(Map<String,Object> payment, PaymentResultV1 result, boolean succeeded) {
    List<Map<String,Object>> rows = store.lockRefundByNumber(result.refundNumber());
    if (rows.isEmpty()) return "IGNORED_REFUND_NOT_FOUND";
    Map<String,Object> refund = rows.getFirst();
    String current = text(refund, "status");
    String target = succeeded ? "SUCCEEDED" : "FAILED";
    if (number(refund.get("payment_id")) != number(payment.get("id"))) return "IGNORED_REFUND_PAYMENT_MISMATCH";
    if (List.of("SUCCEEDED", "FAILED").contains(current)) return "IGNORED_REFUND_ALREADY_" + current;
    store.applyRefundResult(target, "RMT-" + compact(result.resultId(), 24), succeeded ? "" : result.message(),
        succeeded, !succeeded, number(refund.get("id")));
    if (succeeded) {
      if (store.recordRefundedAmount(refund.get("amount"), number(payment.get("id"))) != 1) {
        throw new ProtocolConflictException("Confirmed refund exceeds the paid amount");
      }
      String eventId = "offsite-refund:" + result.refundNumber();
      String payload = json(Map.of("version", 1, "eventId", eventId, "type", "REFUND_SUCCEEDED",
        "recipientId", number(refund.get("customer_id")), "orderId", result.orderId(),
        "reference", result.refundNumber(), "amount", refund.get("amount"), "currency", refund.get("currency")));
      String outboxId = UUID.nameUUIDFromBytes(eventId.getBytes(StandardCharsets.UTF_8)).toString();
      store.insertResultOutbox(outboxId, "REFUND", result.refundNumber(), "OFFSITE_REFUND_SUCCEEDED",
        "offsite-notifications", payload);
    }
    store.insertRefundEvent(number(refund.get("id")), result.resultId(),
      "CALLBACK_" + target, current, target, result.message());
    return "APPLIED_REFUND_" + target;
  }

  private void insertResult(PaymentResultV1 result, String fingerprint, String processId,
                            String processing, String error) {
    store.insertResult(result.resultId(), processId, result.orderId(), result.resultType().name(),
        result.outcome().name(), fingerprint, json(result), processing, error, result.correlationId(),
        result.causationId(), result.traceparent());
  }

  private void insertPaymentNotification(Map<String,Object> payment, PaymentNotice notice,
                                         String digest, String status, String failure) {
    store.insertPaymentNotification(number(payment.get("id")), notice.channel(), notice.notificationId(),
        notice.channelTransactionNo(), status, digest, failure, notice.status());
  }

  private void insertRefundNotification(Map<String,Object> refund, RefundNotice notice,
                                        String digest, String status, String failure) {
    store.insertRefundNotification(number(refund.get("id")), notice.channel(), notice.notificationId(),
        notice.channelRefundNo(), status, digest, failure);
  }

  private void appendOutbox(PaymentResultV1 result, String status) {
    store.insertResultOutbox(result.resultId(),
      result.resultType().name().startsWith("REFUND") ? "REFUND" : "PAYMENT",
      result.refundNumber() == null ? result.paymentNumber() : result.refundNumber(), result.resultType().name(),
      "payment-result-events", json(Map.of("result", result, "applicationStatus", status)));
  }

  private void insertMessage(String id, String kind, String fingerprint) {
    int inserted = store.insertCommandInbox(id, kind, fingerprint);
    if (inserted == 0) {
      throw new ProtocolConflictException("Message id was inserted concurrently");
    }
  }

  private <T> T replay(String id, String fingerprint, Class<T> type) {
    List<Map<String,Object>> rows = store.findCommandInbox(id);
    if (rows.isEmpty()) return null;
    Map<String,Object> row = rows.getFirst();
    if (!fingerprint.equals(row.get("payload_fingerprint"))) {
      throw new ProtocolConflictException("Message id was reused with a different payload");
    }
    String response = nullableText(row, "response_payload");
    if (response == null) throw new ProtocolConflictException("Message is still being processed");
    return mapper.readValue(response, type);
  }

  private <T> T finish(String id, String fingerprint, String status, T response, String error) {
    store.finishCommandInbox(status, json(response), error, id, fingerprint);
    return response;
  }

  private Map<String,Object> merchant(PaymentChannelV1 channel) {
    List<Map<String,Object>> rows = store.findMerchant(channel.name());
    if (rows.isEmpty()) throw new ProtocolConflictException("Payment channel is disabled");
    return rows.getFirst();
  }

  private List<Map<String,Object>> paymentByIdempotency(String key, boolean lock) {
    return store.findByIdempotency(key, lock);
  }

  private PaymentSnapshotV1 snapshot(Map<String,Object> row) {
    return new PaymentSnapshotV1(1, number(row.get("order_id")), text(row, "payment_number"),
      nullableText(row, "merchant_no"), PaymentChannelV1.valueOf(text(row, "channel")),
      nullableText(row, "channel_transaction_no"), text(row, "status"),
      text(row, "failure_reason"), decimal(row, "amount"), decimal(row, "refunded_amount"),
      text(row, "currency"), instant(row, "created_at"), instant(row, "paid_at"),
      instant(row, "expires_at"), nullableText(row, "checkout_url"),
      nullableText(row, "action_type"), nullableText(row, "qr_payload"));
  }

  private String processId(Long orderId) {
    List<String> rows = store.lockProcessIds(orderId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private ContractReceipt receipt(String id, String fingerprint, String status, String traceparent) {
    return new ContractReceipt(id, fingerprint, status, traceparent);
  }

  private String json(Object value) {
    return mapper.writeValueAsString(value);
  }

  private static long number(Object value) {
    return ((Number) value).longValue();
  }

  private static BigDecimal decimal(Map<String,Object> row, String key) {
    return (BigDecimal) row.get(key);
  }

  private static String text(Map<String,Object> row, String key) {
    Object value = row.get(key);
    return value == null ? "" : String.valueOf(value);
  }

  private static String nullableText(Map<String,Object> row, String key) {
    Object value = row.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static Instant instant(Map<String,Object> row, String key) {
    Object value = row.get(key);
    return value instanceof Timestamp timestamp ? timestamp.toInstant() : null;
  }

  private static String compact(String value, int length) {
    String compact = value.replace("-", "").toUpperCase();
    return compact.substring(0, Math.min(length, compact.length()));
  }

  public record ContractReceipt(String messageId, String payloadFingerprint, String status,
                                String traceparent) {
  }

  public record CallbackReceipt(String result) {
  }

  public record RecoveryRequest(String operator, String reason, String traceparent) {
  }

  public record PaymentNotice(String notificationId, String channelTransactionNo, String channel,
                              String status, BigDecimal amount, String currency,
                              String failureReason, String traceparent) {
  }

  public record RefundNotice(String notificationId, String merchantRefundNo, String channelRefundNo,
                             String channel, String status, BigDecimal amount, String currency,
                             String failureReason, String traceparent) {
  }

  public record RefundSnapshot(int schemaVersion, String refundNumber, Long orderId, String status,
                               String inventoryStatus, BigDecimal amount, String currency,
                               String reasonCode, String reasonDetail, String channelRefundNo,
                               String failureReason, Instant requestedAt, Instant succeededAt,
                               Instant failedAt, List<RefundEvent> events) {
  }

  public record RefundEvent(Long id, String eventType, String fromStatus, String toStatus,
                            String detail, Instant createdAt) {
  }

  @ResponseStatus(HttpStatus.CONFLICT)
  public static class ProtocolConflictException extends RuntimeException {

    public ProtocolConflictException(String message) {
      super(message);
    }
  }

  public static class ProtocolNotFoundException extends RuntimeException {

    public ProtocolNotFoundException(String message) {
      super(message);
    }
  }
}
