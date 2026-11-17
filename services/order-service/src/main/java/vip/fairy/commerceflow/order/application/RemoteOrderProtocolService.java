package vip.fairy.commerceflow.order.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.order.application.contract.v1.OrderCommandV1;
import vip.fairy.commerceflow.order.application.contract.v1.OrderContractSemantics;
import vip.fairy.commerceflow.order.application.contract.v1.OrderResultOutcomeV1;
import vip.fairy.commerceflow.order.application.contract.v1.OrderResultV1;

@Service
public class RemoteOrderProtocolService {

  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public RemoteOrderProtocolService(JdbcTemplate jdbc, ObjectMapper mapper,
                                    PlatformTransactionManager manager) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
  }

  public ContractReceipt handle(OrderCommandV1 command) {
    String fingerprint = OrderContractSemantics.fingerprint(command);
    return transactions.execute(status -> {
      ContractReceipt replay = replay(command.commandId(), fingerprint, command.traceparent());
      if (replay != null) return replay;
      int inserted = jdbc.update("INSERT IGNORE INTO order_remote_message_inbox(message_id,message_kind,"
          + "payload_fingerprint,processing_status) VALUES (?,'COMMAND',?,'RECEIVED')",
        command.commandId(), fingerprint);
      if (inserted == 0) return replay(command.commandId(), fingerprint, command.traceparent());
      String current = currentStatus(command.orderId());
      if (!command.expectedStatus().equals(current)) {
        return finish(command.commandId(), fingerprint, "REJECTED_OUT_OF_ORDER",
          "Expected " + command.expectedStatus() + " but was " + current, command.traceparent());
      }
      return switch (command.commandType()) {
        case CANCEL -> cancel(command, fingerprint);
        case CONFIRM_FULFILLMENT -> transition(command, fingerprint, "CONFIRMED",
          "MERCHANT_CONFIRMED", "Merchant accepted the order");
        case SHIP -> ship(command, fingerprint);
        case CONFIRM_RECEIPT -> transition(command, fingerprint, "RECEIVED",
          "ORDER_RECEIVED", "Customer confirmed receipt");
      };
    });
  }

  public ContractReceipt handle(OrderResultV1 result) {
    String fingerprint = OrderContractSemantics.fingerprint(result);
    return transactions.execute(status -> {
      ContractReceipt duplicate = resultReplay(result, fingerprint);
      if (duplicate != null) return duplicate;
      List<Map<String,Object>> process = jdbc.queryForList(
        "SELECT process_id,status,current_step,waiting_for FROM order_process_managers "
          + "WHERE order_id=? AND status IN ('WAITING_RESULT','WAITING_RECONCILIATION') "
          + "ORDER BY created_at DESC LIMIT 1 FOR UPDATE", result.orderId());
      String processId = process.isEmpty() ? null : String.valueOf(process.getFirst().get("process_id"));
      String processing = processId == null ? "UNMATCHED" : "PROCESSED";
      int inserted = jdbc.update("INSERT IGNORE INTO order_result_inbox(result_id,process_id,"
          + "order_id,result_type,outcome,"
          + "payload_fingerprint,payload,processing_status,error_detail,correlation_id,causation_id,"
          + "traceparent,received_at,processed_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,"
          + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
        result.resultId(), processId, result.orderId(), result.resultType().name(),
        result.outcome().name(), fingerprint, json(result), processing,
        result.errorCode(), result.correlationId(), result.causationId(), result.traceparent());
      if (inserted == 0) return resultReplay(result, fingerprint);
      if (processId == null) {
        return receipt(result.resultId(), fingerprint, "UNMATCHED_RESULT", result.traceparent());
      }
      if (result.outcome() != OrderResultOutcomeV1.SUCCEEDED) {
        jdbc.update("UPDATE order_process_managers SET status='COMPENSATION_REQUIRED',"
            + "last_error=?,updated_at=CURRENT_TIMESTAMP(6) WHERE process_id=?",
          result.errorCode() + ": " + result.message(), processId);
        return receipt(result.resultId(), fingerprint, "COMPENSATION_REQUIRED", result.traceparent());
      }
      return applyCancellationResult(result, fingerprint, processId,
        String.valueOf(process.getFirst().get("current_step")));
    });
  }

  public List<Map<String,Object>> recoverable() {
    return jdbc.queryForList("SELECT process_id,order_id,status,current_step,waiting_for,attempts,"
      + "result_deadline_at,last_error,recovered_by,recovery_reason,recovered_at FROM order_process_managers "
      + "WHERE status IN ('WAITING_RECONCILIATION','COMPENSATION_REQUIRED','MANUAL_REVIEW') "
      + "ORDER BY updated_at");
  }

  public ContractReceipt recover(String processId, RecoveryRequest request) {
    if (request.operator() == null || request.operator().isBlank()
        || request.reason() == null || request.reason().isBlank()) {
      throw new IllegalArgumentException("Recovery operator and reason are required");
    }
    return transactions.execute(status -> {
      Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM order_process_managers WHERE process_id=?",
        Integer.class, processId);
      if (found == null || found == 0) throw new IllegalArgumentException("Order process not found");
      if (request.action() == RecoveryAction.RETRY) {
        jdbc.update("UPDATE order_command_outbox SET status='PENDING',attempts=attempts+1,"
          + "next_attempt_at=CURRENT_TIMESTAMP(6),last_error=NULL WHERE process_id=?", processId);
        jdbc.update("UPDATE order_process_managers SET status='WAITING_RESULT',attempts=attempts+1,"
            + "next_check_at=CURRENT_TIMESTAMP(6),result_deadline_at=CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE,"
            + "recovered_by=?,recovery_reason=?,recovered_at=CURRENT_TIMESTAMP(6),"
            + "updated_at=CURRENT_TIMESTAMP(6) WHERE process_id=?",
          request.operator(), request.reason(), processId);
      } else {
        jdbc.update("UPDATE order_process_managers SET status='MANUAL_REVIEW',recovered_by=?,"
            + "recovery_reason=?,recovered_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6) "
            + "WHERE process_id=?", request.operator(), request.reason(), processId);
      }
      return receipt(processId, "", request.action().name(), request.traceparent());
    });
  }

  @Scheduled(fixedDelayString = "${commerceflow.order.protocol.timeout-scan-delay:5s}")
  public void markUnknownResults() {
    jdbc.update("UPDATE order_process_managers SET status='WAITING_RECONCILIATION',"
      + "last_error='RESULT_TIMEOUT_UNKNOWN',updated_at=CURRENT_TIMESTAMP(6) "
      + "WHERE status='WAITING_RESULT' AND result_deadline_at < CURRENT_TIMESTAMP(6)");
  }

  private ContractReceipt cancel(OrderCommandV1 command, String fingerprint) {
    updateOrder(command.orderId(), "PENDING_PAYMENT", "CANCELLING", command.reason(), null, null);
    event(command, "ORDER_CANCELLATION_STARTED", "PENDING_PAYMENT", "CANCELLING", command.reason());
    String processId = "cancel-" + command.orderId() + "-" + command.commandId();
    jdbc.update("INSERT INTO order_process_managers(process_id,order_id,process_type,status,current_step,"
        + "waiting_for,command_id,command_fingerprint,correlation_id,causation_id,traceparent,attempts,"
        + "result_deadline_at,next_check_at,version,created_at,updated_at) VALUES (?,?,"
        + "'CANCELLATION','WAITING_RESULT','WAITING_BOTH','PAYMENT_AND_INVENTORY',?,?,?,?,?,0,"
        + "CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE,CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE,0,"
        + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6))",
      processId, command.orderId(), command.commandId(), fingerprint, command.correlationId(),
      command.causationId(), command.traceparent());
    outbox(command, fingerprint, processId, "payment-orchestration", "CLOSE_PAYMENT");
    outbox(command, fingerprint, processId, "inventory-service", "RELEASE_INVENTORY");
    return finish(command.commandId(), fingerprint, "WAITING_RESULT", null, command.traceparent());
  }

  private ContractReceipt transition(OrderCommandV1 command, String fingerprint, String target,
                                     String eventType, String detail) {
    updateOrder(command.orderId(), command.expectedStatus(), target, null, null, null);
    event(command, eventType, command.expectedStatus(), target, detail);
    return finish(command.commandId(), fingerprint, "APPLIED", null, command.traceparent());
  }

  private ContractReceipt ship(OrderCommandV1 command, String fingerprint) {
    updateOrder(command.orderId(), "CONFIRMED", "SHIPPED", null,
      command.carrier(), command.trackingNumber());
    event(command, "ORDER_SHIPPED", "CONFIRMED", "SHIPPED",
      command.carrier() + " / " + command.trackingNumber());
    return finish(command.commandId(), fingerprint, "APPLIED", null, command.traceparent());
  }

  private ContractReceipt applyCancellationResult(OrderResultV1 result, String fingerprint,
                                                   String processId, String currentStep) {
    boolean payment = result.resultType().name().equals("PAYMENT");
    boolean inventory = result.resultType().name().equals("INVENTORY");
    if (!payment && !inventory) {
      jdbc.update("UPDATE order_result_inbox SET processing_status='REJECTED_OUT_OF_ORDER',"
        + "error_detail='Cancellation waits for PAYMENT or INVENTORY' WHERE result_id=?", result.resultId());
      return receipt(result.resultId(), fingerprint, "REJECTED_OUT_OF_ORDER", result.traceparent());
    }
    if ((payment && "PAYMENT_CONFIRMED".equals(currentStep))
        || (inventory && "INVENTORY_CONFIRMED".equals(currentStep))) {
      jdbc.update("UPDATE order_result_inbox SET processing_status='REJECTED_OUT_OF_ORDER',"
        + "error_detail='Result type was already confirmed' WHERE result_id=?", result.resultId());
      return receipt(result.resultId(), fingerprint, "REJECTED_OUT_OF_ORDER", result.traceparent());
    }
    boolean completes = (payment && "INVENTORY_CONFIRMED".equals(currentStep))
      || (inventory && "PAYMENT_CONFIRMED".equals(currentStep));
    if (completes) {
      updateOrder(result.orderId(), "CANCELLING", "CANCELLED", "Cancellation completed", null, null);
      jdbc.update("UPDATE order_process_managers SET status='COMPLETED',current_step='COMPLETED',"
          + "waiting_for='NONE',updated_at=CURRENT_TIMESTAMP(6),completed_at=CURRENT_TIMESTAMP(6) "
          + "WHERE process_id=?", processId);
      jdbc.update("UPDATE order_command_outbox SET status='ACKNOWLEDGED',"
        + "acknowledged_at=CURRENT_TIMESTAMP(6) WHERE process_id=?", processId);
      return receipt(result.resultId(), fingerprint, "COMPLETED", result.traceparent());
    }
    String step = payment ? "PAYMENT_CONFIRMED" : "INVENTORY_CONFIRMED";
    String waiting = payment ? "INVENTORY" : "PAYMENT";
    jdbc.update("UPDATE order_process_managers SET current_step=?,waiting_for=?,"
      + "updated_at=CURRENT_TIMESTAMP(6) WHERE process_id=?", step, waiting, processId);
    return receipt(result.resultId(), fingerprint, "WAITING_RESULT", result.traceparent());
  }

  private void outbox(OrderCommandV1 command, String fingerprint, String processId,
                      String target, String type) {
    String id = UUID.nameUUIDFromBytes((command.commandId() + ":" + target).getBytes()).toString();
    jdbc.update("INSERT INTO order_command_outbox(command_id,process_id,order_id,target_service,"
        + "command_type,payload_fingerprint,payload,status,attempts,next_attempt_at,result_deadline_at,"
        + "correlation_id,causation_id,traceparent,created_at) VALUES (?,?,?,?,?,?,?,'PENDING',0,"
        + "CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6) + INTERVAL 5 MINUTE,?,?,?,CURRENT_TIMESTAMP(6))",
      id, processId, command.orderId(), target, type, fingerprint, json(command),
      command.correlationId(), command.commandId(), command.traceparent());
  }

  private void event(OrderCommandV1 command, String type, String from, String to, String detail) {
    jdbc.update("INSERT INTO order_events(order_id,actor_id,event_type,from_status,to_status,detail,"
        + "created_at,trigger_source,scheduled_task_id) VALUES (?,?,?,?,?,?,CURRENT_TIMESTAMP(6),"
        + "'REMOTE_COMMAND',NULL)", command.orderId(), command.actorId(), type, from, to, detail);
  }

  private void updateOrder(Long orderId, String from, String to, String reason,
                           String carrier, String tracking) {
    int changed = jdbc.update("UPDATE shop_orders SET status=?,cancel_reason=COALESCE(?,cancel_reason),"
        + "carrier=COALESCE(?,carrier),tracking_number=COALESCE(?,tracking_number),"
        + "cancelled_at=CASE WHEN ?='CANCELLED' THEN CURRENT_TIMESTAMP(6) ELSE cancelled_at END,"
        + "confirmed_at=CASE WHEN ?='CONFIRMED' THEN CURRENT_TIMESTAMP(6) ELSE confirmed_at END,"
        + "shipped_at=CASE WHEN ?='SHIPPED' THEN CURRENT_TIMESTAMP(6) ELSE shipped_at END,"
        + "received_at=CASE WHEN ?='RECEIVED' THEN CURRENT_TIMESTAMP(6) ELSE received_at END,"
        + "updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND status=?",
      to, reason, carrier, tracking, to, to, to, to, orderId, from);
    if (changed != 1) throw new ProtocolConflictException("Order status changed concurrently");
  }

  private String currentStatus(Long orderId) {
    List<String> rows = jdbc.query("SELECT status FROM shop_orders WHERE id=? FOR UPDATE",
      (result, row) -> result.getString(1), orderId);
    if (rows.isEmpty()) throw new ProtocolConflictException("Order not found");
    return rows.getFirst();
  }

  private ContractReceipt replay(String messageId, String fingerprint, String traceparent) {
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT payload_fingerprint,processing_status "
      + "FROM order_remote_message_inbox WHERE message_id=?", messageId);
    if (rows.isEmpty()) return null;
    if (!fingerprint.equals(rows.getFirst().get("payload_fingerprint"))) {
      throw new ProtocolConflictException("Message id was reused with a different payload");
    }
    return receipt(messageId, fingerprint, "DUPLICATE_" + rows.getFirst().get("processing_status"),
      traceparent);
  }

  private ContractReceipt resultReplay(OrderResultV1 result, String fingerprint) {
    List<Map<String,Object>> rows = jdbc.queryForList("SELECT payload_fingerprint,processing_status "
      + "FROM order_result_inbox WHERE result_id=?", result.resultId());
    if (rows.isEmpty()) return null;
    if (!fingerprint.equals(rows.getFirst().get("payload_fingerprint"))) {
      throw new ProtocolConflictException("Result id was reused with a different payload");
    }
    return receipt(result.resultId(), fingerprint, "DUPLICATE_" + rows.getFirst().get("processing_status"),
      result.traceparent());
  }

  private ContractReceipt finish(String id, String fingerprint, String status, String error,
                                 String traceparent) {
    ContractReceipt receipt = receipt(id, fingerprint, status, traceparent);
    jdbc.update("UPDATE order_remote_message_inbox SET processing_status=?,response_payload=?,"
        + "error_detail=?,processed_at=CURRENT_TIMESTAMP(6) WHERE message_id=?",
      status, json(receipt), error, id);
    return receipt;
  }

  private ContractReceipt receipt(String id, String fingerprint, String status, String traceparent) {
    return new ContractReceipt(id, fingerprint, status, traceparent);
  }

  private String json(Object value) {
    return mapper.writeValueAsString(value);
  }

  public record ContractReceipt(String messageId, String payloadFingerprint, String status,
                                String traceparent) {
  }

  public record RecoveryRequest(RecoveryAction action, String operator, String reason,
                                String traceparent) {
  }

  public enum RecoveryAction { RETRY, MANUAL_REVIEW }

  public static class ProtocolConflictException extends RuntimeException {

    public ProtocolConflictException(String message) {
      super(message);
    }
  }
}
