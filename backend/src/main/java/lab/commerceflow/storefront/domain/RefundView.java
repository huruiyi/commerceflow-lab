package lab.commerceflow.storefront.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class RefundView {
  private Long id; private String refundNumber; private Long orderId; private Long paymentId; private Long customerId;
  private String idempotencyKey; private String requestFingerprint; private BigDecimal amount; private String currency;
  private String reasonCode; private String reasonDetail; private String status; private String orderStatusBeforeRefund;
  private String channelRefundNo; private String failureReason; private Instant requestedAt; private Instant succeededAt;
  private Instant failedAt; private Instant createdAt; private Instant updatedAt; private int version;
  public Long getId(){return id;} public String getRefundNumber(){return refundNumber;} public Long getOrderId(){return orderId;}
  public Long getPaymentId(){return paymentId;} public Long getCustomerId(){return customerId;} public String getIdempotencyKey(){return idempotencyKey;}
  public String getRequestFingerprint(){return requestFingerprint;} public BigDecimal getAmount(){return amount;} public String getCurrency(){return currency;}
  public String getReasonCode(){return reasonCode;} public String getReasonDetail(){return reasonDetail;} public String getStatus(){return status;}
  public String getOrderStatusBeforeRefund(){return orderStatusBeforeRefund;} public String getChannelRefundNo(){return channelRefundNo;}
  public String getFailureReason(){return failureReason;} public Instant getRequestedAt(){return requestedAt;} public Instant getSucceededAt(){return succeededAt;}
  public Instant getFailedAt(){return failedAt;} public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;} public int getVersion(){return version;}
}
