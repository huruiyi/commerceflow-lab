package lab.commerceflow.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("channel_refund")
public class ChannelRefund {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String channelRefundNo;
  private Long merchantId;
  private String merchantRefundNo;
  private String channelTransactionNo;
  private BigDecimal amount;
  private String currency;
  private String reasonCode;
  private String status;
  private String faultScenario;
  private String failureReason;
  private LocalDateTime succeededAt;
  private LocalDateTime failedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long value) {
    id = value;
  }

  public String getChannelRefundNo() {
    return channelRefundNo;
  }

  public void setChannelRefundNo(String value) {
    channelRefundNo = value;
  }

  public Long getMerchantId() {
    return merchantId;
  }

  public void setMerchantId(Long value) {
    merchantId = value;
  }

  public String getMerchantRefundNo() {
    return merchantRefundNo;
  }

  public void setMerchantRefundNo(String value) {
    merchantRefundNo = value;
  }

  public String getChannelTransactionNo() {
    return channelTransactionNo;
  }

  public void setChannelTransactionNo(String value) {
    channelTransactionNo = value;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal value) {
    amount = value;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String value) {
    currency = value;
  }

  public String getReasonCode() {
    return reasonCode;
  }

  public void setReasonCode(String value) {
    reasonCode = value;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String value) {
    status = value;
  }

  public String getFaultScenario() {
    return faultScenario;
  }

  public void setFaultScenario(String value) {
    faultScenario = value;
  }

  public String getFailureReason() {
    return failureReason;
  }

  public void setFailureReason(String value) {
    failureReason = value;
  }

  public LocalDateTime getSucceededAt() {
    return succeededAt;
  }

  public void setSucceededAt(LocalDateTime value) {
    succeededAt = value;
  }

  public LocalDateTime getFailedAt() {
    return failedAt;
  }

  public void setFailedAt(LocalDateTime value) {
    failedAt = value;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime value) {
    createdAt = value;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime value) {
    updatedAt = value;
  }
}
