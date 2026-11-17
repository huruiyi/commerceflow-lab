package vip.fairy.commerceflow.commerce.ordering.application.contract.v1;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class OrderContractSemantics {

  private OrderContractSemantics() {
  }

  public static String fingerprint(OrderCommandV1 command) {
    return sha256(String.join("|",
      Integer.toString(command.schemaVersion()), command.commandId(), command.commandType().name(),
      command.occurredAt().toString(), command.producer(), command.orderId().toString(),
      command.actorId().toString(), command.expectedStatus(), value(command.reason()),
      value(command.carrier()), value(command.trackingNumber()), command.correlationId(),
      command.causationId(), command.traceparent()));
  }

  public static String fingerprint(OrderResultV1 result) {
    return sha256(String.join("|",
      Integer.toString(result.schemaVersion()), result.resultId(), result.resultType().name(),
      result.outcome().name(), result.occurredAt().toString(), result.producer(),
      result.orderId().toString(), result.externalReference(), result.sourceStatus(),
      value(result.errorCode()), result.message(), result.correlationId(), result.causationId(),
      result.traceparent()));
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
