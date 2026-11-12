package lab.commerceflow.storefront.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class NewShopOrder {
  private Long id; private final String orderNumber; private final Long customerId;
  private final BigDecimal totalAmount; private final String currency;
  private final Instant paymentDueAt;
  public NewShopOrder(String orderNumber, Long customerId, BigDecimal totalAmount, String currency,
                      Instant paymentDueAt) {
    this.orderNumber=orderNumber; this.customerId=customerId; this.totalAmount=totalAmount;
    this.currency=currency; this.paymentDueAt=paymentDueAt;
  }
  public Long getId(){return id;} public void setId(Long id){this.id=id;}
  public String getOrderNumber(){return orderNumber;} public Long getCustomerId(){return customerId;}
  public BigDecimal getTotalAmount(){return totalAmount;} public String getCurrency(){return currency;}
  public Instant getPaymentDueAt(){return paymentDueAt;}
}
