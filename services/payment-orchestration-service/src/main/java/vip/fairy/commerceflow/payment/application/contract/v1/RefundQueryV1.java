package vip.fairy.commerceflow.payment.application.contract.v1;

public record RefundQueryV1(int schemaVersion, String refundNumber) {

  public static final int VERSION = 1;

  public void validate() {
    if (schemaVersion != VERSION) {
      throw new IllegalArgumentException("Unsupported refund query schema version");
    }
    if (refundNumber == null || refundNumber.isBlank()) {
      throw new IllegalArgumentException("Refund number is required");
    }
  }
}
