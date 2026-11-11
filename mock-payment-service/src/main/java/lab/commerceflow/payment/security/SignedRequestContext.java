package lab.commerceflow.payment.security;

import lab.commerceflow.payment.domain.PaymentMerchant;

public record SignedRequestContext(PaymentMerchant merchant, String requestId) {
  public static final String ATTRIBUTE = SignedRequestContext.class.getName();
}
