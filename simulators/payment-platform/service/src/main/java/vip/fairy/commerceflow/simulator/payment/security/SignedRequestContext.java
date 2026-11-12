package vip.fairy.commerceflow.simulator.payment.security;

import vip.fairy.commerceflow.simulator.payment.domain.PaymentMerchant;

public record SignedRequestContext(PaymentMerchant merchant, String requestId) {
  public static final String ATTRIBUTE = SignedRequestContext.class.getName();
}
