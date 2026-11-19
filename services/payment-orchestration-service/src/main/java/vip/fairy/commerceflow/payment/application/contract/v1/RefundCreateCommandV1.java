package vip.fairy.commerceflow.payment.application.contract.v1;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

public record RefundCreateCommandV1(
  int schemaVersion,
  String commandId,
  Long orderId,
  Long customerId,
  String idempotencyKey,
  String reasonCode,
  String reasonDetail,
  String correlationId,
  String causationId,
  String traceparent,
  @JsonInclude(JsonInclude.Include.NON_NULL) String afterSaleId,
  @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal amount
) {

  public RefundCreateCommandV1(int schemaVersion, String commandId, Long orderId, Long customerId,
                               String idempotencyKey, String reasonCode, String reasonDetail,
                               String correlationId, String causationId, String traceparent) {
    this(schemaVersion, commandId, orderId, customerId, idempotencyKey, reasonCode, reasonDetail,
      correlationId, causationId, traceparent, null, null);
  }

  public void validate() {
    if (schemaVersion != 1 || blank(commandId) || orderId == null || orderId <= 0
        || customerId == null || customerId <= 0 || blank(idempotencyKey) || blank(reasonCode)
        || blank(correlationId) || blank(causationId) || blank(traceparent)
        || ((afterSaleId == null) != (amount == null))
        || (afterSaleId != null && (!afterSaleId.equals(idempotencyKey)
          || !afterSaleId.matches("[0-9a-f-]{36}") || amount.signum() <= 0 || amount.scale() > 2))
        || !PaymentCreateCommandV1.TRACEPARENT.matcher(traceparent).matches()) {
      throw new IllegalArgumentException("Refund create command is invalid");
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
