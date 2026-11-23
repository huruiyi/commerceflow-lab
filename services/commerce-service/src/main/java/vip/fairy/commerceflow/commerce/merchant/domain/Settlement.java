package vip.fairy.commerceflow.commerce.merchant.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/** Merchant earning, refund reversal, and frozen statement facts. */
public final class Settlement {

  private Settlement() {
  }

  public record Account(Long merchantId, int commissionBps, Instant enabledAt) {
    public BigDecimal commission(BigDecimal merchandise) {
      return merchandise.multiply(BigDecimal.valueOf(commissionBps, 4)).setScale(2, RoundingMode.HALF_UP);
    }
  }

  public record Entry(String sourceKey, Long merchantId, Long orderId, Long orderLineId, String kind,
                      BigDecimal merchandise, BigDecimal freight, BigDecimal commission,
                      Instant occurredAt, String statementId) {
  }

  public record Statement(String id, Long merchantId, Instant startsAt, Instant cutoffAt, BigDecimal merchandise,
                          BigDecimal freight, BigDecimal commission, BigDecimal payable, Long frozenBy) {
  }

  public record Rollup(long orderCount, BigDecimal merchandise, BigDecimal freight, BigDecimal commission) {
    public BigDecimal payable() {
      return merchandise.add(freight).subtract(commission);
    }
  }

  public record RefundRollup(long count, BigDecimal amount, BigDecimal commissionReversed,
                             long priorCount, BigDecimal priorAmount) {
  }

  public record RefundOrigin(String sourceKey, Long orderId, Long orderLineId, Instant occurredAt,
                             BigDecimal amount, BigDecimal commissionReversed,
                             String saleSourceKey, String originStatementId, Instant originCutoffAt) {
  }
}
