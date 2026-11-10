package com.tanerinal.education.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class ShopOrderView {
  private Long id; private String orderNumber; private Long customerId; private String status;
  private BigDecimal totalAmount; private String currency; private Instant createdAt; private Instant updatedAt;
  public Long getId(){return id;} public String getOrderNumber(){return orderNumber;} public Long getCustomerId(){return customerId;}
  public String getStatus(){return status;} public BigDecimal getTotalAmount(){return totalAmount;} public String getCurrency(){return currency;}
  public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
