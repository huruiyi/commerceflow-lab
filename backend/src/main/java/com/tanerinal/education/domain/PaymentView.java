package com.tanerinal.education.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class PaymentView {
  private Long id; private Long orderId; private String paymentNumber, idempotencyKey, status, failureReason;
  private BigDecimal amount; private Instant createdAt;
  public Long getId(){return id;} public Long getOrderId(){return orderId;} public String getPaymentNumber(){return paymentNumber;}
  public String getIdempotencyKey(){return idempotencyKey;} public String getStatus(){return status;}
  public String getFailureReason(){return failureReason;} public BigDecimal getAmount(){return amount;} public Instant getCreatedAt(){return createdAt;}
}
