package vip.fairy.commerceflow.commerce.ordering.application;

import java.math.BigDecimal;

public class CheckoutPriceChangedException extends RuntimeException {

  private final BigDecimal currentTotal;

  public CheckoutPriceChangedException(BigDecimal currentTotal) {
    super("The cart total changed. Review the updated cart before checking out.");
    this.currentTotal = currentTotal;
  }

  public BigDecimal currentTotal() {
    return currentTotal;
  }
}
