package vip.fairy.commerceflow.commerce.merchant.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Durable attempt to pay a frozen merchant statement. */
public record MerchantPayout(String id, String statementId, Long merchantId, BigDecimal amount, String currency,
                             String status, boolean simulateFailure, Long requestedBy, String channelPayoutNo,
                             Instant createdAt, Instant resolvedAt, String recoveryOf, String recoveryReason) {

  public MerchantPayout merchantView() {
    return new MerchantPayout(id, statementId, merchantId, amount, currency, status, simulateFailure,
      requestedBy, channelPayoutNo, createdAt, resolvedAt, recoveryOf, null);
  }
}
