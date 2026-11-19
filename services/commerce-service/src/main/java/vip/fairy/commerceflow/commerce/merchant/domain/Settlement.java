package vip.fairy.commerceflow.commerce.merchant.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Merchant earning, refund reversal, and frozen statement facts. */
public final class Settlement {

  private Settlement() {
  }

  public record Account(Long merchantId, int commissionBps, Instant enabledAt) {
  }

  public record Entry(String sourceKey, Long merchantId, Long orderId, Long orderLineId, String kind,
                      BigDecimal merchandise, BigDecimal freight, BigDecimal commission,
                      Instant occurredAt, String statementId) {
  }

  public record Statement(String id, Long merchantId, Instant startsAt, Instant cutoffAt, BigDecimal merchandise,
                          BigDecimal freight, BigDecimal commission, BigDecimal payable, Long frozenBy) {
  }
}
