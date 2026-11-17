package vip.fairy.commerceflow.commerce.payment.application.contract.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class PaymentContractSemantics {

  private PaymentContractSemantics() {
  }

  public static String requestFingerprint(PaymentCreateCommandV1 command) {
    return sha256(String.join("|", command.customerId().toString(), command.orderId().toString(),
      command.channel().name(), command.amount().stripTrailingZeros().toPlainString(),
      command.currency(), Boolean.toString(command.simulateFailure())));
  }

  public static String fingerprint(PaymentCreateCommandV1 command) {
    return sha256(String.join("|", Integer.toString(command.schemaVersion()), command.commandId(),
      command.occurredAt().toString(), command.producer(), command.customerId().toString(),
      command.orderId().toString(), command.orderNumber(), command.channel().name(),
      command.amount().stripTrailingZeros().toPlainString(), command.currency(),
      command.paymentDueAt().toString(), command.idempotencyKey(),
      Boolean.toString(command.simulateFailure()), command.correlationId(), command.causationId(),
      command.traceparent()));
  }

  public static String fingerprint(PaymentCloseCommandV1 command) {
    return sha256(String.join("|", Integer.toString(command.schemaVersion()), command.commandId(),
      command.occurredAt().toString(), command.producer(), command.orderId().toString(),
      command.reason(), command.correlationId(), command.causationId(), command.traceparent()));
  }

  public static String fingerprint(PaymentResultV1 result) {
    return sha256(String.join("|", Integer.toString(result.schemaVersion()), result.resultId(),
      result.resultType().name(), result.outcome().name(), result.occurredAt().toString(),
      result.producer(), result.orderId().toString(), result.paymentNumber(),
      value(result.refundNumber()), result.sourceStatus(), value(result.errorCode()),
      result.message(), result.correlationId(), result.causationId(), result.traceparent()));
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String value(String value) {
    return value == null ? "" : value;
  }
}
