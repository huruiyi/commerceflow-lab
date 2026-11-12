package vip.fairy.commerceflow.commerce.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class PaymentView {
  private Long id;
  private Long orderId;
  private Long merchantAccountId;
  private String paymentNumber;
  private String merchantNo;
  private String idempotencyKey;
  private String channel;
  private String currency;
  private String requestFingerprint;
  private String channelTransactionNo;
  private BigDecimal amount;
  private BigDecimal refundedAmount;
  private String status;
  private String failureReason;
  private Instant createdAt;
  private Instant paidAt;
  private Instant expiresAt;
  private String checkoutUrl;
  private String actionType;
  private String qrPayload;

  public Long getId() {
    return id;
  }

  public Long getOrderId() {
    return orderId;
  }

  public Long getMerchantAccountId() {
    return merchantAccountId;
  }

  public String getPaymentNumber() {
    return paymentNumber;
  }

  public String getMerchantNo() {
    return merchantNo;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getChannel() {
    return channel;
  }

  public String getCurrency() {
    return currency;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public String getChannelTransactionNo() {
    return channelTransactionNo;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public BigDecimal getRefundedAmount() {
    return refundedAmount;
  }

  public String getStatus() {
    return status;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPaidAt() {
    return paidAt;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public String getCheckoutUrl() {
    return checkoutUrl;
  }

  public String getActionType() {
    return actionType;
  }

  public String getQrPayload() {
    return qrPayload;
  }
}
