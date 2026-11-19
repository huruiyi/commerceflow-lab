package vip.fairy.commerceflow.commerce.ordering.application;

import java.util.List;

public class CheckoutStockException extends RuntimeException {

  private final List<Line> lines;

  public CheckoutStockException(List<Line> lines) {
    super("Stock changed. Review the affected products before checking out.");
    this.lines = List.copyOf(lines);
  }

  public List<Line> lines() {
    return lines;
  }

  public record Line(Long productId, String productName, int requested, Integer available, boolean listed) {
  }
}
