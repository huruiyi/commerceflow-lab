package vip.fairy.commerceflow.commerce.payment.application.contract.v1;

public record PaymentQueryV1(int schemaVersion, Long orderId) {

  public static final int VERSION = 1;

  public void validate() {
    if (schemaVersion != VERSION || orderId == null || orderId <= 0) {
      throw new IllegalArgumentException("Payment query V1 is invalid");
    }
  }
}
