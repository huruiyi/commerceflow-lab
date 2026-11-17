package vip.fairy.commerceflow.commerce.ordering.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class NewOrder {

  /** Database-generated order identity. */
  private Long id;
  /** Stable customer-facing order number. */
  private final String orderNumber;
  /** Stable customer identity captured at checkout. */
  private final Long customerId;
  /** Order total in the declared currency. */
  private final BigDecimal totalAmount;
  /** ISO 4217 order currency code. */
  private final String currency;
  /** Latest instant at which the order may enter payment. */
  private final Instant paymentDueAt;

  public NewOrder(String orderNumber, Long customerId, BigDecimal totalAmount, String currency,
                  Instant paymentDueAt) {
    this.orderNumber = orderNumber;
    this.customerId = customerId;
    this.totalAmount = totalAmount;
    this.currency = currency;
    this.paymentDueAt = paymentDueAt;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public Long getCustomerId() {
    return customerId;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public Instant getPaymentDueAt() {
    return paymentDueAt;
  }
}
