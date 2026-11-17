package vip.fairy.commerceflow.payment.application;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
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

@Service
public class RemotePaymentProtocolService {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final ChannelGatewayClient gateway;

  public RemotePaymentProtocolService(JdbcTemplate jdbc, ObjectMapper mapper,
                                      PlatformTransactionManager manager,
                                      ChannelGatewayClient gateway) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
    this.gateway = gateway;
  }

  public PaymentSnapshotV1 create(PaymentCreateCommandV1 command) {
    command.validate();
    String fingerprint = PaymentContractSemantics.fingerprint(command);
    return transactions.execute(status -> {
      PaymentSnapshotV1 replay = replay(command.commandId(), fingerprint, PaymentSnapshotV1.class);
      if (replay != null) return replay;
      insertMessage(command.commandId(), "CREATE", fingerprint);
      List<Map<String,Object>> existing = paymentByIdempotency(command.idempotencyKey(), true);
      if (!existing.isEmpty()) {
        if (!PaymentContractSemantics.requestFingerprint(command)
          .equals(existing.getFirst().get("request_fingerprint"))) {
          throw new ProtocolConflictException(
            "Idempotency key was reused with different payment parameters");
        }
        return finish(command.commandId(), fingerprint, "IDEMPOTENT_REPLAY",
          snapshot(existing.getFirst()), null);
      }
      Map<String,Object> merchant = merchant(command.channel());
      String paymentNumber = "PAY-RMT-" + compact(command.commandId(), 20);
      ChannelGatewayClient.Result remote = gateway.create(paymentNumber, command.channel(),
        "Order " + command.orderNumber(), command.amount(), command.currency(),
        command.simulateFailure());
      String requestFingerprint = PaymentContractSemantics.requestFingerprint(command);
      jdbc.update("INSERT IGNORE INTO payment_idempotency_locks(idempotency_key,created_at) "
        + "VALUES (?,CURRENT_TIMESTAMP(6))", command.idempotencyKey());
      jdbc.update("INSERT INTO payment_records(order_id,payment_number,idempotency_key,amount,status,"
          + "failure_reason,created_at,merchant_account_id,channel,currency,request_fingerprint,"
          + "channel_transaction_no,paid_at,expires_at,updated_at,checkout_url,action_type,qr_payload,"
          + "version,refunded_amount) VALUES (?,?,?,?,'PENDING','',CURRENT_TIMESTAMP(6),?,?,?,?,?,NULL,?,"
          + "CURRENT_TIMESTAMP(6),?, 'REDIRECT',NULL,0,0)",
        command.orderId(), paymentNumber, command.idempotencyKey(), command.amount(),
        merchant.get("id"), command.channel().name(), command.currency(), requestFingerprint,
        remote.channelTransactionNo(), Timestamp.from(remote.expiresInstant()), remote.checkoutUrl());
      Map<String,Object> payment = paymentByIdempotency(command.idempotencyKey(), true).getFirst();
      long paymentId = number(payment.get("id"));
      event("payment_events", "payment_id", paymentId, UUID.randomUUID().toString(),
        "PAYMENT_PENDING", "CREATED", "PENDING", "Remote Payment V1 accepted the command");
      String processId = "payment-" + command.commandId();
      jdbc.update("INSERT INTO payment_process_managers(process_id,order_id,process_type,status,"
          + "command_id,command_fingerprint,correlation_id,causation_id,traceparent,result_deadline_at,"
          + "next_check_at,version,created_at,updated_at) VALUES (?,?,'PAYMENT_CREATE','WAITING_RESULT',"
          + "?,?,?,?,?,CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE,CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE,"
          + "0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
        processId, command.orderId(), command.commandId(), fingerprint, command.correlationId(),
        command.causationId(), command.traceparent());
      String childCommandId = UUID.nameUUIDFromBytes(
        (command.commandId() + ":channel-result").getBytes(StandardCharsets.UTF_8)).toString();
      jdbc.update("INSERT INTO payment_command_outbox(command_id,process_id,order_id,command_type,"
          + "payload_fingerprint,payload,status,attempts,next_attempt_at,result_deadline_at,correlation_id,"
          + "causation_id,traceparent,created_at) VALUES (?,?,?,'AWAIT_CHANNEL_RESULT',?,?,"
          + "'IN_FLIGHT',1,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE,?,?,?,"
          + "CURRENT_TIMESTAMP(6))",
        childCommandId, processId, command.orderId(), fingerprint, json(command),
        command.correlationId(), command.commandId(), command.traceparent());
      return finish(command.commandId(), fingerprint, "WAITING_RESULT", snapshot(payment), null);
    });
  }

  public Optional<PaymentSnapshotV1> latest(PaymentQueryV1 query) {
    query.validate();
    List<Map<String,Object>> rows = jdbc.queryForList(paymentSelect()
      + " WHERE p.order_id=? ORDER BY p.id DESC LIMIT 1", query.orderId());
    return rows.isEmpty() ? Optional.empty() : Optional.of(snapshot(rows.getFirst()));
  }

  public Optional<RefundSnapshot> refund(PaymentQueryV1 query) {
    query.validate();
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM refund_records "
      + "WHERE order_id=? ORDER BY id DESC LIMIT 1", query.orderId());
    if (rows.isEmpty()) return Optional.empty();
    return Optional.of(refundSnapshot(rows.getFirst()));
  }

  public Optional<RefundSnapshot> refund(RefundQueryV1 query) {
    query.validate();
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM refund_records "
      + "WHERE refund_number=? LIMIT 1", query.refundNumber());
    return rows.isEmpty() ? Optional.empty() : Optional.of(refundSnapshot(rows.getFirst()));
  }

  private RefundSnapshot refundSnapshot(Map<String,Object> row) {
    List<RefundEvent> events = jdbc.query("SELECT id,event_type,from_status,to_status,detail,created_at "
        + "FROM refund_events WHERE refund_id=? ORDER BY id",
      (result, index) -> new RefundEvent(result.getLong("id"), result.getString("event_type"),
        result.getString("from_status"), result.getString("to_status"), result.getString("detail"),
        result.getTimestamp("created_at").toInstant()), number(row.get("id")));
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
      List<Map<String,Object>> payments = jdbc.queryForList(paymentSelect()
        + " WHERE p.order_id=? ORDER BY p.id FOR UPDATE", command.orderId());
      boolean succeeded = payments.stream().anyMatch(row -> "SUCCESS".equals(row.get("status")));
      int closed = 0;
      if (!succeeded) {
        for (Map<String,Object> payment : payments) {
          if (!"PENDING".equals(payment.get("status"))) continue;
          gateway.close(PaymentChannelV1.valueOf(text(payment, "channel")),
            text(payment, "channel_transaction_no"));
          int changed = jdbc.update("UPDATE payment_records SET status='CLOSED',failure_reason=?,"
              + "updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=? AND status='PENDING'",
            command.reason(), number(payment.get("id")));
          if (changed == 1) {
            closed++;
            event("payment_events", "payment_id", number(payment.get("id")),
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
    jdbc.update("UPDATE payment_command_outbox SET status='ACKNOWLEDGED',"
        + "acknowledged_at=COALESCE(acknowledged_at,CURRENT_TIMESTAMP(6)),last_error=NULL "
        + "WHERE process_id IN (SELECT process_id FROM payment_process_managers "
        + "WHERE order_id=? AND process_type='PAYMENT_CREATE') "
        + "AND status IN ('NEW','IN_FLIGHT','RETRY','DEAD')", orderId);
    jdbc.update("UPDATE payment_process_managers SET status='COMPLETED',last_error=NULL,"
        + "completed_at=COALESCE(completed_at,CURRENT_TIMESTAMP(6)),"
        + "updated_at=CURRENT_TIMESTAMP(6),version=version+1 "
        + "WHERE order_id=? AND process_type='PAYMENT_CREATE' AND status<>'COMPLETED'", orderId);
  }

  public ContractReceipt result(PaymentResultV1 result) {
    result.validate();
    return transactions.execute(status -> applyResult(result));
  }

  public CallbackReceipt paymentCallback(PaymentNotice notice, String rawPayload) {
    return transactions.execute(status -> {
      String digest = PaymentContractSemantics.sha256(rawPayload);
      List<Map<String,Object>> duplicate = jdbc.queryForList("SELECT processing_status,payload_digest FROM "
          + "payment_notifications WHERE channel=? AND notification_id=?",
        notice.channel(), notice.notificationId());
      if (!duplicate.isEmpty()) {
        if (!digest.equals(duplicate.getFirst().get("payload_digest"))) {
          throw new ProtocolConflictException(
            "Payment notification id was reused with a different payload");
        }
        return new CallbackReceipt("ACKNOWLEDGED");
      }
      List<Map<String,Object>> rows = jdbc.queryForList(paymentSelect()
        + " WHERE p.channel_transaction_no=? FOR UPDATE", notice.channelTransactionNo());
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
      List<Map<String,Object>> duplicate = jdbc.queryForList("SELECT processing_status,payload_digest FROM "
          + "refund_notifications WHERE channel=? AND notification_id=?",
        notice.channel(), notice.notificationId());
      if (!duplicate.isEmpty()) {
        if (!digest.equals(duplicate.getFirst().get("payload_digest"))) {
          throw new ProtocolConflictException(
            "Refund notification id was reused with a different payload");
        }
        return new CallbackReceipt("ACKNOWLEDGED");
      }
      List<Map<String,Object>> rows = jdbc.queryForList("SELECT r.*,p.payment_number,"
        + "p.channel AS payment_channel FROM refund_records r "
        + "JOIN payment_records p ON p.id=r.payment_id WHERE r.refund_number=? FOR UPDATE",
        notice.merchantRefundNo());
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
    return jdbc.queryForList("SELECT process_id,order_id,status,process_type,command_id,"
      + "result_deadline_at,last_error,recovered_by,recovery_reason,recovered_at "
      + "FROM payment_process_managers WHERE status IN "
      + "('RECONCILIATION_REQUIRED','COMPENSATION_REQUIRED') ORDER BY updated_at");
  }

  public ContractReceipt recover(String processId, RecoveryRequest request) {
    if (request.operator() == null || request.operator().isBlank()
        || request.reason() == null || request.reason().isBlank()) {
      throw new IllegalArgumentException("Recovery operator and reason are required");
    }
    return transactions.execute(status -> {
      int changed = jdbc.update("UPDATE payment_process_managers SET status='WAITING_RESULT',"
          + "result_deadline_at=CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE,"
          + "next_check_at=CURRENT_TIMESTAMP(6),recovered_by=?,recovery_reason=?,"
          + "recovered_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6),version=version+1 "
          + "WHERE process_id=? AND status IN ('RECONCILIATION_REQUIRED','COMPENSATION_REQUIRED')",
        request.operator(), request.reason(), processId);
      if (changed != 1) throw new ProtocolConflictException("Payment process is not recoverable");
      jdbc.update("UPDATE payment_command_outbox SET status='RETRY',attempts=attempts+1,"
        + "next_attempt_at=CURRENT_TIMESTAMP(6),last_error=NULL WHERE process_id=?", processId);
      return new ContractReceipt(processId, "", "RETRY", request.traceparent());
    });
  }

  @Scheduled(fixedDelayString = "${commerceflow.payment.protocol.timeout-scan-delay:5s}")
  public void markUnknownResults() {
    jdbc.update("UPDATE payment_process_managers SET status='RECONCILIATION_REQUIRED',"
      + "last_error='RESULT_TIMEOUT_UNKNOWN',updated_at=CURRENT_TIMESTAMP(6),version=version+1 "
      + "WHERE status='WAITING_RESULT' AND result_deadline_at < CURRENT_TIMESTAMP(6)");
  }

  private ContractReceipt applyResult(PaymentResultV1 result) {
    String fingerprint = PaymentContractSemantics.fingerprint(result);
    List<Map<String,Object>> replay = jdbc.queryForList("SELECT payload_fingerprint,processing_status "
      + "FROM payment_result_inbox WHERE result_id=?", result.resultId());
    if (!replay.isEmpty()) {
      if (!fingerprint.equals(replay.getFirst().get("payload_fingerprint"))) {
        throw new ProtocolConflictException("Result id was reused with a different payload");
      }
      return receipt(result.resultId(), fingerprint,
        "DUPLICATE_" + replay.getFirst().get("processing_status"), result.traceparent());
    }
    List<Map<String,Object>> payments = jdbc.queryForList(paymentSelect()
      + " WHERE p.payment_number=? FOR UPDATE", result.paymentNumber());
    if (payments.isEmpty()) {
      insertResult(result, fingerprint, null, "IGNORED", "Payment was not found");
      return receipt(result.resultId(), fingerprint, "UNMATCHED", result.traceparent());
    }
    Map<String,Object> payment = payments.getFirst();
    String processId = processId(result.orderId());
    if (result.outcome() != PaymentResultOutcomeV1.SUCCEEDED) {
      String processStatus = result.outcome() == PaymentResultOutcomeV1.RECONCILIATION_REQUIRED
        ? "RECONCILIATION_REQUIRED" : "COMPENSATION_REQUIRED";
      insertResult(result, fingerprint, processId, "IGNORED", result.errorCode());
      if (processId != null) {
        jdbc.update("UPDATE payment_process_managers SET status=?,last_error=?,"
            + "updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE process_id=?",
          processStatus, result.errorCode() + ": " + result.message(), processId);
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
      jdbc.update("UPDATE payment_process_managers SET status='COMPLETED',completed_at=CURRENT_TIMESTAMP(6),"
        + "updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE process_id=?", processId);
      jdbc.update("UPDATE payment_command_outbox SET status='ACKNOWLEDGED',"
        + "acknowledged_at=CURRENT_TIMESTAMP(6) WHERE process_id=?", processId);
    }
    appendOutbox(result, applied);
    return receipt(result.resultId(), fingerprint, applied, result.traceparent());
  }

  private String applyPaymentSuccess(Map<String,Object> payment, PaymentResultV1 result) {
    String current = text(payment, "status");
    if ("SUCCESS".equals(current)) return "IGNORED_ALREADY_SUCCESSFUL";
    jdbc.update("UPDATE payment_records SET status='SUCCESS',failure_reason='',"
        + "paid_at=COALESCE(paid_at,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6),"
        + "version=version+1 WHERE id=?", number(payment.get("id")));
    event("payment_events", "payment_id", number(payment.get("id")), result.resultId(),
      "CALLBACK_SUCCESS", current, "SUCCESS", result.message());
    return "APPLIED_PAYMENT_SUCCEEDED";
  }

  private String applyPaymentTerminal(Map<String,Object> payment, PaymentResultV1 result,
                                      String target) {
    String current = text(payment, "status");
    if ("SUCCESS".equals(current)) return "IGNORED_PAYMENT_ALREADY_SUCCEEDED";
    if (target.equals(current)) return "IGNORED_ALREADY_" + target;
    jdbc.update("UPDATE payment_records SET status=?,failure_reason=?,updated_at=CURRENT_TIMESTAMP(6),"
      + "version=version+1 WHERE id=?", target, result.message(), number(payment.get("id")));
    event("payment_events", "payment_id", number(payment.get("id")), result.resultId(),
      "CALLBACK_" + target, current, target, result.message());
    return "APPLIED_PAYMENT_" + target;
  }

  private String applyRefund(Map<String,Object> payment, PaymentResultV1 result, boolean succeeded) {
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM refund_records "
      + "WHERE refund_number=? FOR UPDATE", result.refundNumber());
    if (rows.isEmpty()) return "IGNORED_REFUND_NOT_FOUND";
    Map<String,Object> refund = rows.getFirst();
    String current = text(refund, "status");
    String target = succeeded ? "SUCCEEDED" : "FAILED";
    if (target.equals(current)) return "IGNORED_REFUND_ALREADY_" + target;
    jdbc.update("UPDATE refund_records SET status=?,channel_refund_no=COALESCE(channel_refund_no,?),"
        + "failure_reason=?,succeeded_at=CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE succeeded_at END,"
        + "failed_at=CASE WHEN ? THEN failed_at ELSE CURRENT_TIMESTAMP(6) END,"
        + "updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?",
      target, "RMT-" + compact(result.resultId(), 24), succeeded ? "" : result.message(), succeeded,
      succeeded, number(refund.get("id")));
    if (succeeded) {
      jdbc.update("UPDATE payment_records SET refunded_amount=GREATEST(refunded_amount,?),"
        + "updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=?",
        refund.get("amount"), number(payment.get("id")));
    }
    event("refund_events", "refund_id", number(refund.get("id")), result.resultId(),
      "CALLBACK_" + target, current, target, result.message());
    return "APPLIED_REFUND_" + target;
  }

  private void insertResult(PaymentResultV1 result, String fingerprint, String processId,
                            String processing, String error) {
    jdbc.update("INSERT INTO payment_result_inbox(result_id,process_id,order_id,result_type,outcome,"
        + "payload_fingerprint,payload,processing_status,error_detail,correlation_id,causation_id,"
        + "traceparent,received_at,processed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(6),"
        + "CURRENT_TIMESTAMP(6))",
      result.resultId(), processId, result.orderId(), result.resultType().name(), result.outcome().name(),
      fingerprint, json(result), processing, error, result.correlationId(), result.causationId(),
      result.traceparent());
  }

  private void insertPaymentNotification(Map<String,Object> payment, PaymentNotice notice,
                                         String digest, String status, String failure) {
    jdbc.update("INSERT INTO payment_notifications(payment_id,channel,notification_id,"
        + "channel_transaction_no,signature_valid,processing_status,payload_digest,failure_reason,"
        + "received_at,processed_at,raw_status,retry_count,next_retry_at) VALUES (?,?,?,?,1,?,?,?,"
        + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),?,0,NULL)",
      number(payment.get("id")), notice.channel(), notice.notificationId(),
      notice.channelTransactionNo(), status, digest, failure, notice.status());
  }

  private void insertRefundNotification(Map<String,Object> refund, RefundNotice notice,
                                        String digest, String status, String failure) {
    jdbc.update("INSERT INTO refund_notifications(refund_id,channel,notification_id,channel_refund_no,"
        + "signature_valid,processing_status,payload_digest,failure_reason,received_at,processed_at) "
        + "VALUES (?,?,?,?,1,?,?,?,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
      number(refund.get("id")), notice.channel(), notice.notificationId(), notice.channelRefundNo(),
      status, digest, failure);
  }

  private void appendOutbox(PaymentResultV1 result, String status) {
    jdbc.update("INSERT IGNORE INTO payment_outbox(event_id,aggregate_type,aggregate_id,event_type,"
        + "topic,payload,status,attempts,next_attempt_at,created_at,published_at) VALUES (?,?,?,?,?,?,'NEW',"
        + "0,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6),NULL)",
      result.resultId(), result.resultType().name().startsWith("REFUND") ? "REFUND" : "PAYMENT",
      result.refundNumber() == null ? result.paymentNumber() : result.refundNumber(),
      result.resultType().name(), "payment-result-events", json(Map.of(
        "result", result, "applicationStatus", status)));
  }

  private void insertMessage(String id, String kind, String fingerprint) {
    int inserted = jdbc.update("INSERT IGNORE INTO payment_remote_message_inbox(message_id,message_kind,"
      + "payload_fingerprint,processing_status) VALUES (?,?,?,'RECEIVED')", id, kind, fingerprint);
    if (inserted == 0) {
      throw new ProtocolConflictException("Message id was inserted concurrently");
    }
  }

  private <T> T replay(String id, String fingerprint, Class<T> type) {
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT payload_fingerprint,response_payload "
      + "FROM payment_remote_message_inbox WHERE message_id=?", id);
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
    jdbc.update("UPDATE payment_remote_message_inbox SET processing_status=?,response_payload=?,"
        + "error_detail=?,processed_at=CURRENT_TIMESTAMP(6) WHERE message_id=? AND payload_fingerprint=?",
      status, json(response), error, id, fingerprint);
    return response;
  }

  private Map<String,Object> merchant(PaymentChannelV1 channel) {
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT id,merchant_no FROM "
      + "payment_merchant_accounts WHERE channel=? AND enabled=1", channel.name());
    if (rows.isEmpty()) throw new ProtocolConflictException("Payment channel is disabled");
    return rows.getFirst();
  }

  private List<Map<String,Object>> paymentByIdempotency(String key, boolean lock) {
    return jdbc.queryForList(paymentSelect() + " WHERE p.idempotency_key=?" + (lock ? " FOR UPDATE" : ""),
      key);
  }

  private String paymentSelect() {
    return "SELECT p.*,m.merchant_no FROM payment_records p LEFT JOIN payment_merchant_accounts m "
      + "ON m.id=p.merchant_account_id";
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
    List<String> rows = jdbc.query("SELECT process_id FROM payment_process_managers WHERE order_id=? "
        + "AND status IN ('WAITING_RESULT','RECONCILIATION_REQUIRED') ORDER BY created_at DESC "
        + "LIMIT 1 FOR UPDATE", (result, row) -> result.getString(1), orderId);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void event(String table, String foreignKey, long aggregateId, String eventId,
                     String type, String from, String to, String detail) {
    jdbc.update("INSERT INTO " + table + "(" + foreignKey + ",event_id,event_type,from_status,to_status,"
        + "detail,created_at) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(6))",
      aggregateId, eventId, type, from, to, detail);
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
