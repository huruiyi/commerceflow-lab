package vip.fairy.commerceflow.order.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.order.application.contract.v1.OrderCommandV1;
import vip.fairy.commerceflow.order.application.contract.v1.OrderContractSemantics;
import vip.fairy.commerceflow.order.application.contract.v1.OrderResultOutcomeV1;
import vip.fairy.commerceflow.order.application.contract.v1.OrderResultV1;
import vip.fairy.commerceflow.order.infrastructure.persistence.OrderProtocolMapper;

@Service
public class RemoteOrderProtocolService {

  private final OrderProtocolMapper store;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public RemoteOrderProtocolService(OrderProtocolMapper store, ObjectMapper mapper,
                                    PlatformTransactionManager manager) {
    this.store = store;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(manager);
  }

  public ContractReceipt handle(OrderCommandV1 command) {
    String fingerprint = OrderContractSemantics.fingerprint(command);
    return transactions.execute(status -> {
      ContractReceipt replay = replay(command.commandId(), fingerprint, command.traceparent());
      if (replay != null) return replay;
      int inserted = store.insertCommand(command.commandId(), fingerprint);
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
      List<Map<String,Object>> process = store.lockWaitingProcess(result.orderId());
      String processId = process.isEmpty() ? null : String.valueOf(process.getFirst().get("process_id"));
      String processing = processId == null ? "UNMATCHED" : "PROCESSED";
      int inserted = store.insertResult(result.resultId(), processId, result.orderId(),
        result.resultType().name(), result.outcome().name(), fingerprint, json(result), processing,
        result.errorCode(), result.correlationId(), result.causationId(), result.traceparent());
      if (inserted == 0) return resultReplay(result, fingerprint);
      if (processId == null) {
        return receipt(result.resultId(), fingerprint, "UNMATCHED_RESULT", result.traceparent());
      }
      if (result.outcome() != OrderResultOutcomeV1.SUCCEEDED) {
        store.requireCompensation(result.errorCode() + ": " + result.message(), processId);
        return receipt(result.resultId(), fingerprint, "COMPENSATION_REQUIRED", result.traceparent());
      }
      return applyCancellationResult(result, fingerprint, processId,
        String.valueOf(process.getFirst().get("current_step")));
    });
  }

  public List<Map<String,Object>> recoverable() {
    return store.recoverable();
  }

  public ContractReceipt recover(String processId, RecoveryRequest request) {
    if (request.operator() == null || request.operator().isBlank()
        || request.reason() == null || request.reason().isBlank()) {
      throw new IllegalArgumentException("Recovery operator and reason are required");
    }
    return transactions.execute(status -> {
      Integer found = store.countProcess(processId);
      if (found == null || found == 0) throw new IllegalArgumentException("Order process not found");
      if (request.action() == RecoveryAction.RETRY) {
        store.resetOutbox(processId);
        store.retryProcess(request.operator(), request.reason(), processId);
      } else {
        store.markManualReview(request.operator(), request.reason(), processId);
      }
      return receipt(processId, "", request.action().name(), request.traceparent());
    });
  }

  @Scheduled(fixedDelayString = "${commerceflow.order.protocol.timeout-scan-delay:5s}")
  public void markUnknownResults() {
    store.markUnknownResults();
  }

  private ContractReceipt cancel(OrderCommandV1 command, String fingerprint) {
    updateOrder(command.orderId(), "PENDING_PAYMENT", "CANCELLING", command.reason(), null, null);
    event(command, "ORDER_CANCELLATION_STARTED", "PENDING_PAYMENT", "CANCELLING", command.reason());
    String processId = "cancel-" + command.orderId() + "-" + command.commandId();
    store.insertCancellation(processId, command.orderId(), command.commandId(), fingerprint,
        command.correlationId(), command.causationId(), command.traceparent());
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
      store.rejectResult(result.resultId());
      return receipt(result.resultId(), fingerprint, "REJECTED_OUT_OF_ORDER", result.traceparent());
    }
    if ((payment && "PAYMENT_CONFIRMED".equals(currentStep))
        || (inventory && "INVENTORY_CONFIRMED".equals(currentStep))) {
      store.rejectConfirmedResult(result.resultId());
      return receipt(result.resultId(), fingerprint, "REJECTED_OUT_OF_ORDER", result.traceparent());
    }
    boolean completes = (payment && "INVENTORY_CONFIRMED".equals(currentStep))
      || (inventory && "PAYMENT_CONFIRMED".equals(currentStep));
    if (completes) {
      updateOrder(result.orderId(), "CANCELLING", "CANCELLED", "Cancellation completed", null, null);
      store.completeProcess(processId);
      store.acknowledgeOutbox(processId);
      return receipt(result.resultId(), fingerprint, "COMPLETED", result.traceparent());
    }
    String step = payment ? "PAYMENT_CONFIRMED" : "INVENTORY_CONFIRMED";
    String waiting = payment ? "INVENTORY" : "PAYMENT";
    store.advanceProcess(step, waiting, processId);
    return receipt(result.resultId(), fingerprint, "WAITING_RESULT", result.traceparent());
  }

  private void outbox(OrderCommandV1 command, String fingerprint, String processId,
                      String target, String type) {
    String id = UUID.nameUUIDFromBytes((command.commandId() + ":" + target).getBytes()).toString();
    store.insertOutbox(id, processId, command.orderId(), target, type, fingerprint, json(command),
        command.correlationId(), command.commandId(), command.traceparent());
  }

  private void event(OrderCommandV1 command, String type, String from, String to, String detail) {
    store.insertEvent(command.orderId(), command.actorId(), type, from, to, detail);
  }

  private void updateOrder(Long orderId, String from, String to, String reason,
                           String carrier, String tracking) {
    int changed = store.transition(to, reason, carrier, tracking, orderId, from);
    if (changed != 1) throw new ProtocolConflictException("Order status changed concurrently");
  }

  private String currentStatus(Long orderId) {
    List<String> rows = store.lockStatus(orderId);
    if (rows.isEmpty()) throw new ProtocolConflictException("Order not found");
    return rows.getFirst();
  }

  private ContractReceipt replay(String messageId, String fingerprint, String traceparent) {
    List<Map<String,Object>> rows = store.findCommandReceipt(messageId);
    if (rows.isEmpty()) return null;
    if (!fingerprint.equals(rows.getFirst().get("payload_fingerprint"))) {
      throw new ProtocolConflictException("Message id was reused with a different payload");
    }
    return receipt(messageId, fingerprint, "DUPLICATE_" + rows.getFirst().get("processing_status"),
      traceparent);
  }

  private ContractReceipt resultReplay(OrderResultV1 result, String fingerprint) {
    List<Map<String,Object>> rows = store.findResultReceipt(result.resultId());
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
    store.finishCommand(status, json(receipt), error, id);
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
