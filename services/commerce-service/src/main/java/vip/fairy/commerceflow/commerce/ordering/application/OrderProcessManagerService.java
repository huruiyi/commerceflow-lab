package vip.fairy.commerceflow.commerce.ordering.application;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderCommandV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderContractSemantics;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderResultOutcomeV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderResultTypeV1;
import vip.fairy.commerceflow.commerce.ordering.application.contract.v1.OrderResultV1;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderProcessView;
import vip.fairy.commerceflow.commerce.ordering.domain.OrderResultInboxView;
import vip.fairy.commerceflow.commerce.ordering.infrastructure.OrderProcessManagerMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class OrderProcessManagerService {

  private final OrderProcessManagerMapper processes;
  private final ObjectMapper mapper;

  public OrderProcessManagerService(OrderProcessManagerMapper processes, ObjectMapper mapper) {
    this.processes = processes;
    this.mapper = mapper;
  }

  @Transactional
  public StartResult start(OrderCommandV1 command, OrderResultTypeV1 waitingFor,
                           String targetService, Instant resultDeadline) {
    command.validate();
    if (waitingFor == null || blank(targetService) || resultDeadline == null
        || !resultDeadline.isAfter(Instant.now())) {
      throw new IllegalArgumentException("Order process target and future deadline are required");
    }
    String fingerprint = OrderContractSemantics.fingerprint(command);
    OrderProcessView existing = processes.lockProcess(command.commandId()).orElse(null);
    if (existing != null) {
      requireSameCommand(existing, fingerprint);
      return new StartResult(existing.getProcessId(), false);
    }
    try {
      String payload = mapper.writeValueAsString(command);
      processes.insertProcess(command.commandId(), command.orderId(), command.commandType().name(),
        waitingFor.name(), command.commandId(), fingerprint, command.correlationId(),
        command.causationId(), command.traceparent(), resultDeadline);
      processes.insertOutbox(command.commandId(), command.commandId(), command.orderId(),
        targetService, command.commandType().name(), payload, fingerprint, resultDeadline,
        command.correlationId(), command.causationId(), command.traceparent());
      return new StartResult(command.commandId(), true);
    } catch (DuplicateKeyException exception) {
      OrderProcessView concurrent = processes.lockProcess(command.commandId())
        .orElseThrow(() -> exception);
      requireSameCommand(concurrent, fingerprint);
      return new StartResult(concurrent.getProcessId(), false);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Order command V1 cannot be persisted", exception);
    }
  }

  @Transactional
  public Acceptance accept(OrderResultV1 result) {
    result.validate();
    String fingerprint = OrderContractSemantics.fingerprint(result);
    OrderResultInboxView existing = processes.result(result.resultId()).orElse(null);
    if (existing != null) {
      return duplicate(existing, fingerprint);
    }
    try {
      processes.insertResult(result.resultId(), result.orderId(), result.resultType().name(),
        result.outcome().name(), mapper.writeValueAsString(result), fingerprint,
        result.correlationId(), result.causationId(), result.traceparent());
    } catch (DuplicateKeyException exception) {
      return duplicate(processes.result(result.resultId()).orElseThrow(() -> exception),
        fingerprint);
    } catch (Exception exception) {
      throw new IllegalArgumentException("Order result V1 cannot be persisted", exception);
    }

    OrderProcessView process = processes.lockActive(result.orderId(), result.resultType().name())
      .orElse(null);
    if (process == null) {
      processes.attachResult(result.resultId(), null, "IGNORED",
        "No process is waiting for this result type");
      return Acceptance.IGNORED;
    }
    String targetStatus = switch (result.outcome()) {
      case SUCCEEDED -> "COMPLETED";
      case REJECTED -> "COMPENSATION_REQUIRED";
      case FAILED -> "RECONCILIATION_REQUIRED";
    };
    String error = result.outcome() == OrderResultOutcomeV1.SUCCEEDED
      ? null : result.errorCode() + ": " + result.message();
    if (processes.finishProcess(process.getProcessId(), targetStatus, error) != 1) {
      processes.attachResult(result.resultId(), process.getProcessId(), "IGNORED",
        "Process state changed concurrently");
      return Acceptance.IGNORED;
    }
    processes.attachResult(result.resultId(), process.getProcessId(), "APPLIED", error);
    processes.acknowledgeCommand(process.getCommandId());
    return Acceptance.APPLIED;
  }

  @Scheduled(
    fixedDelayString = "${commerceflow.order-process.recovery-delay:5000}",
    initialDelayString = "${commerceflow.order-process.recovery-initial-delay:5000}"
  )
  @Transactional
  public void markExpiredUnknownResults() {
    processes.markExpired(Instant.now());
  }

  @Transactional
  public void recover(String processId, String operator, String reason, Instant nextCheckAt) {
    if (blank(processId) || blank(operator) || blank(reason) || nextCheckAt == null) {
      throw new IllegalArgumentException("Process, operator, reason and next check time are required");
    }
    if (processes.recover(processId, operator, reason, nextCheckAt) != 1) {
      throw new IllegalStateException("Order process is not recoverable");
    }
  }

  private Acceptance duplicate(OrderResultInboxView existing, String fingerprint) {
    if (!existing.getPayloadFingerprint().equals(fingerprint)) {
      throw new IllegalArgumentException("Order result ID was reused with a different payload");
    }
    return Acceptance.DUPLICATE;
  }

  private void requireSameCommand(OrderProcessView existing, String fingerprint) {
    if (!existing.getCommandFingerprint().equals(fingerprint)) {
      throw new IllegalArgumentException("Order command ID was reused with a different payload");
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public enum Acceptance {
    APPLIED,
    DUPLICATE,
    IGNORED
  }

  public record StartResult(String processId, boolean created) {
  }
}
