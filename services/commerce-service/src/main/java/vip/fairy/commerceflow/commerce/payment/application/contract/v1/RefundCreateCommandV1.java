package vip.fairy.commerceflow.commerce.payment.application.contract.v1;

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
  String traceparent
) {

  public static final int VERSION = 1;

  public void validate() {
    if (schemaVersion != VERSION || blank(commandId) || orderId == null || orderId <= 0
        || customerId == null || customerId <= 0 || blank(idempotencyKey) || blank(reasonCode)
        || blank(correlationId) || blank(causationId) || blank(traceparent)
        || !PaymentCreateCommandV1.TRACEPARENT.matcher(traceparent).matches()) {
      throw new IllegalArgumentException("Refund create command V1 is invalid");
    }
  }

  private boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
