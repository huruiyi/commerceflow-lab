package vip.fairy.commerceflow.commerce.ordering.application;

public class CheckoutPriceChangedException extends RuntimeException {

  private final CheckoutQuote quote;

  public CheckoutPriceChangedException(CheckoutQuote quote) {
    super("The checkout quote changed. Review the current amounts before submitting.");
    this.quote = quote;
  }

  public CheckoutQuote quote() {
    return quote;
  }
}
