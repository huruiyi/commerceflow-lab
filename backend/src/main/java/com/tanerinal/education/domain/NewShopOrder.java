package com.tanerinal.education.domain;

import java.math.BigDecimal;

public class NewShopOrder {
  private Long id; private final String orderNumber; private final Long customerId;
  private final BigDecimal totalAmount; private final String currency;
  public NewShopOrder(String orderNumber, Long customerId, BigDecimal totalAmount, String currency) {
    this.orderNumber=orderNumber; this.customerId=customerId; this.totalAmount=totalAmount; this.currency=currency;
  }
  public Long getId(){return id;} public void setId(Long id){this.id=id;}
  public String getOrderNumber(){return orderNumber;} public Long getCustomerId(){return customerId;}
  public BigDecimal getTotalAmount(){return totalAmount;} public String getCurrency(){return currency;}
}
