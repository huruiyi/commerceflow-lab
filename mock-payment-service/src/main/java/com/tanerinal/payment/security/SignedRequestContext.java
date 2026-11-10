package com.tanerinal.payment.security;

import com.tanerinal.payment.domain.PaymentMerchant;

public record SignedRequestContext(PaymentMerchant merchant, String requestId) {
  public static final String ATTRIBUTE = SignedRequestContext.class.getName();
}
