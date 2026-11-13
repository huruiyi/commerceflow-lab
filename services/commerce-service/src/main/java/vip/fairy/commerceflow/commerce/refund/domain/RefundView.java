package vip.fairy.commerceflow.commerce.refund.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 商城退款记录的持久化投影。
 */
public class RefundView {
  /** 退款记录自增主键。 */
  private Long id;
  /** 商城生成的对外退款单号。 */
  private String refundNumber;
  /** 退款所属商城订单主键。 */
  private Long orderId;
  /** 被退款的成功支付记录主键。 */
  private Long paymentId;
  /** 申请退款的客户用户主键。 */
  private Long customerId;
  /** 客户端退款请求幂等键。 */
  private String idempotencyKey;
  /** 退款请求关键字段的 SHA-256 指纹。 */
  private String requestFingerprint;
  /** 退款金额，单位由 currency 指定。 */
  private BigDecimal amount;
  /** ISO 4217 货币代码，例如 CNY。 */
  private String currency;
  /** 结构化退款原因代码。 */
  private String reasonCode;
  /** 客户填写的退款原因补充说明。 */
  private String reasonDetail;
  /** 退款状态：PROCESSING、SUCCEEDED 或 FAILED。 */
  private String status;
  /** 进入 REFUNDING 之前的订单状态，用于失败回滚。 */
  private String orderStatusBeforeRefund;
  /** 支付渠道返回的退款单号。 */
  private String channelRefundNo;
  /** 渠道退款失败原因。 */
  private String failureReason;
  /** 退款请求创建时间。 */
  private Instant requestedAt;
  /** 渠道确认退款成功时间。 */
  private Instant succeededAt;
  /** 渠道确认退款失败时间。 */
  private Instant failedAt;
  /** 退款记录创建时间。 */
  private Instant createdAt;
  /** 退款记录最后更新时间。 */
  private Instant updatedAt;
  /** 条件更新使用的乐观版本号。 */
  private int version;

  public Long getId() {
    return id;
  }

  public String getRefundNumber() {
    return refundNumber;
  }

  public Long getOrderId() {
    return orderId;
  }

  public Long getPaymentId() {
    return paymentId;
  }

  public Long getCustomerId() {
    return customerId;
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public String getCurrency() {
    return currency;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public String getReasonDetail() {
    return reasonDetail;
  }

  public String getStatus() {
    return status;
  }

  public String getOrderStatusBeforeRefund() {
    return orderStatusBeforeRefund;
  }

  public String getChannelRefundNo() {
    return channelRefundNo;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public Instant getRequestedAt() {
    return requestedAt;
  }

  public Instant getSucceededAt() {
    return succeededAt;
  }

  public Instant getFailedAt() {
    return failedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public int getVersion() {
    return version;
  }
}
