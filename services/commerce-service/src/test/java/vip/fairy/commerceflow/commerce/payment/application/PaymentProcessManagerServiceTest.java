package vip.fairy.commerceflow.commerce.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import tools.jackson.databind.ObjectMapper;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentChannelV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentContractSemantics;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentCreateCommandV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentResultOutcomeV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentResultTypeV1;
import vip.fairy.commerceflow.commerce.payment.application.contract.v1.PaymentResultV1;
import vip.fairy.commerceflow.commerce.payment.domain.PaymentProcessView;
import vip.fairy.commerceflow.commerce.payment.infrastructure.PaymentProcessMapper;

@ExtendWith(MockitoExtension.class)
class PaymentProcessManagerServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-17T00:00:00Z");
  private static final String TRACEPARENT =
    "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  @Mock
  private PaymentProcessMapper processes;
  private PaymentProcessManagerService service;

  @BeforeEach
  void setUp() {
    service = new PaymentProcessManagerService(processes, new ObjectMapper(),
      Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void repeatedCommandWithSamePayloadIsIdempotent() {
    PaymentCreateCommandV1 command = command();
    PaymentProcessView existing = process("WAITING_RESULT",
      PaymentContractSemantics.fingerprint(command));
    when(processes.findByCommandId(command.commandId())).thenReturn(existing);

    assertEquals(existing, service.start(command, Duration.ofMinutes(5)));
    verify(processes, never()).insertProcess(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void repeatedCommandIdWithDifferentPayloadIsRejected() {
    PaymentCreateCommandV1 command = command();
    when(processes.findByCommandId(command.commandId())).thenReturn(process("WAITING_RESULT", "other"));

    assertThrows(IllegalStateException.class,
      () -> service.start(command, Duration.ofMinutes(5)));
  }

  @Test
  void repeatedResultWithSamePayloadIsIdempotent() {
    PaymentResultV1 result = result(PaymentResultOutcomeV1.SUCCEEDED);
    when(processes.resultFingerprint(result.resultId()))
      .thenReturn(PaymentContractSemantics.fingerprint(result));
    when(processes.findByCorrelation(result.orderId(), result.correlationId()))
      .thenReturn(process("COMPLETED", "fingerprint"));

    assertEquals("COMPLETED", service.accept(result).status());
    verify(processes, never()).insertResult(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
      any(), any());
  }

  @Test
  void repeatedResultIdWithDifferentPayloadIsRejected() {
    PaymentResultV1 result = result(PaymentResultOutcomeV1.SUCCEEDED);
    when(processes.resultFingerprint(result.resultId())).thenReturn("other");

    assertThrows(IllegalStateException.class, () -> service.accept(result));
  }

  @Test
  void unknownResultIsQuarantinedForReconciliation() {
    PaymentResultV1 result = result(PaymentResultOutcomeV1.RECONCILIATION_REQUIRED);

    assertThrows(PaymentProcessManagerService.UnknownPaymentResultException.class,
      () -> service.accept(result));
    verify(processes).insertResult(eq(result.resultId()), eq(null), eq(result.orderId()),
      eq(result.resultType().name()), eq(result.outcome().name()), any(), any(), eq("CONFLICT"),
      eq("UNKNOWN_PROCESS"), eq(result.correlationId()), eq(result.causationId()),
      eq(result.traceparent()));
  }

  @Test
  void timeoutMovesUnknownResultToReconciliation() {
    when(processes.markExpired(NOW)).thenReturn(1);

    assertEquals(1, service.markExpired());
  }

  @Test
  void manualRetryRequiresAuditAndRequeuesCommand() {
    when(processes.retry("process-1", "operator-a", "provider checked",
      NOW.plus(Duration.ofMinutes(5)))).thenReturn(1);
    when(processes.findByProcessId("process-1")).thenReturn(process("WAITING_RESULT", "fingerprint"));

    PaymentProcessView recovered = service.retry("process-1", "operator-a", "provider checked",
      Duration.ofMinutes(5));

    assertEquals("WAITING_RESULT", recovered.status());
    verify(processes).requeueCommand(eq("process-1"), eq(NOW.plus(Duration.ofMinutes(5))));
  }

  private static PaymentCreateCommandV1 command() {
    return new PaymentCreateCommandV1(1, "11111111-1111-1111-1111-111111111111", NOW,
      "order-service", 7L, 9L, "O-9", PaymentChannelV1.ALIPAY, new BigDecimal("12.30"),
      "CNY", NOW.plusSeconds(600), "idem-9", false, "correlation-9", "cause-9",
      TRACEPARENT);
  }

  private static PaymentResultV1 result(PaymentResultOutcomeV1 outcome) {
    return new PaymentResultV1(1, "22222222-2222-2222-2222-222222222222",
      PaymentResultTypeV1.PAYMENT_SUCCEEDED, outcome, NOW, "payment-orchestration-service", 9L,
      "P-9", null, "SUCCESS", outcome == PaymentResultOutcomeV1.SUCCEEDED ? null : "UNKNOWN",
      "result", "correlation-9", "cause-result-9", TRACEPARENT);
  }

  private static PaymentProcessView process(String status, String fingerprint) {
    return new PaymentProcessView("process-1", 9L, status,
      "11111111-1111-1111-1111-111111111111", fingerprint, "correlation-9",
      NOW.plusSeconds(300), null, null, null);
  }
}
