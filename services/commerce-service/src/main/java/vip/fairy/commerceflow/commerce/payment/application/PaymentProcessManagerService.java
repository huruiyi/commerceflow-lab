package vip.fairy.commerceflow.commerce.payment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentContractSemantics;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentResultOutcomeV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentResultV1;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentProcessView;
import vip.fairy.commerceflow.commerce.payment.infrastructure.PaymentProcessMapper;

@Service
public class PaymentProcessManagerService {

  private final PaymentProcessMapper processes;
  private final ObjectMapper mapper;
  private final Clock clock;

  @Autowired
  public PaymentProcessManagerService(PaymentProcessMapper processes, ObjectMapper mapper) {
    this(processes, mapper, Clock.systemUTC());
  }

  PaymentProcessManagerService(PaymentProcessMapper processes, ObjectMapper mapper, Clock clock) {
    this.processes = processes;
    this.mapper = mapper;
    this.clock = clock;
  }

  @Transactional
  public PaymentProcessView start(PaymentCreateCommandV1 command, Duration resultTimeout) {
    command.validate();
    requirePositive(resultTimeout);
    String fingerprint = PaymentContractSemantics.fingerprint(command);
    PaymentProcessView existing = processes.findByCommandId(command.commandId());
    if (existing != null) {
      requireSame(fingerprint, existing.commandFingerprint(), "Command ID payload conflict");
      return existing;
    }
    try {
      String processId = UUID.randomUUID().toString();
      Instant deadline = clock.instant().plus(resultTimeout);
      String payload = mapper.writeValueAsString(command);
      processes.insertProcess(processId, command.orderId(), command.commandId(), fingerprint,
        command.correlationId(), command.causationId(), command.traceparent(), deadline);
      processes.insertCommand(command.commandId(), processId, command.orderId(), fingerprint,
        payload, command.correlationId(), command.causationId(), command.traceparent(), deadline);
      return processes.findByProcessId(processId);
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalArgumentException("Payment command cannot be serialized", exception);
    }
  }

  @Transactional(noRollbackFor = UnknownPaymentResultException.class)
  public PaymentProcessView accept(PaymentResultV1 result) {
    result.validate();
    String fingerprint = PaymentContractSemantics.fingerprint(result);
    String existingFingerprint = processes.resultFingerprint(result.resultId());
    if (existingFingerprint != null) {
      requireSame(fingerprint, existingFingerprint, "Result ID payload conflict");
      return processes.findByCorrelation(result.orderId(), result.correlationId());
    }
    PaymentProcessView process = processes.findByCorrelation(result.orderId(), result.correlationId());
    if (process == null) {
      persistResult(result, fingerprint, null, "CONFLICT", "UNKNOWN_PROCESS");
      throw new UnknownPaymentResultException("Unknown payment result requires reconciliation");
    }
    persistResult(result, fingerprint, process.processId(), "APPLIED", null);
    processes.applyResult(process.processId(), targetStatus(result.outcome()), result.errorCode());
    return processes.findByProcessId(process.processId());
  }

  private void persistResult(PaymentResultV1 result, String fingerprint, String processId,
                             String processingStatus, String errorDetail) {
    try {
      processes.insertResult(result.resultId(), processId, result.orderId(),
        result.resultType().name(), result.outcome().name(), fingerprint,
        mapper.writeValueAsString(result), processingStatus, errorDetail, result.correlationId(),
        result.causationId(), result.traceparent());
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalArgumentException("Payment result cannot be serialized", exception);
    }
  }

  @Transactional
  public int markExpired() {
    return processes.markExpired(clock.instant());
  }

  @Transactional
  public PaymentProcessView retry(String processId, String operator, String reason,
                                  Duration resultTimeout) {
    if (operator == null || operator.isBlank() || reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Manual recovery requires operator and reason");
    }
    requirePositive(resultTimeout);
    Instant deadline = clock.instant().plus(resultTimeout);
    if (processes.retry(processId, operator, reason, deadline) != 1) {
      throw new IllegalStateException("Only reconciliation-required processes can be retried");
    }
    processes.requeueCommand(processId, deadline);
    return processes.findByProcessId(processId);
  }

  private static String targetStatus(PaymentResultOutcomeV1 outcome) {
    return switch (outcome) {
      case SUCCEEDED -> "COMPLETED";
      case REJECTED -> "COMPENSATION_REQUIRED";
      case RECONCILIATION_REQUIRED -> "RECONCILIATION_REQUIRED";
    };
  }

  private static void requireSame(String actual, String expected, String message) {
    if (!actual.equals(expected)) {
      throw new IllegalStateException(message);
    }
  }

  private static void requirePositive(Duration duration) {
    if (duration == null || duration.isZero() || duration.isNegative()) {
      throw new IllegalArgumentException("Result timeout must be positive");
    }
  }

  public static final class UnknownPaymentResultException extends IllegalStateException {

    public UnknownPaymentResultException(String message) {
      super(message);
    }
  }
}
