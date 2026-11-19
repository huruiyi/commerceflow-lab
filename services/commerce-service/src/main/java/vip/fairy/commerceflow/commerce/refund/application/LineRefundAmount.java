package vip.fairy.commerceflow.commerce.refund.application;

import java.math.BigDecimal;
import java.math.BigInteger;

final class LineRefundAmount {

  private LineRefundAmount() {
  }

  static BigDecimal calculate(BigDecimal net, int purchased, int previous, int requested) {
    if (net.signum() < 0 || purchased <= 0 || previous < 0 || requested <= 0
        || requested > purchased - previous) {
      throw new IllegalArgumentException("Requested quantity exceeds the refundable order line");
    }
    BigInteger cents = net.movePointRight(2).toBigIntegerExact();
    BigInteger quantity = BigInteger.valueOf(purchased);
    BigInteger prior = cents.multiply(BigInteger.valueOf(previous)).divide(quantity);
    BigInteger next = cents.multiply(BigInteger.valueOf(previous + requested)).divide(quantity);
    return new BigDecimal(next.subtract(prior), 2);
  }
}
