package com.tanerinal.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("channel_transaction")
public class ChannelTransaction {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String channelTransactionNo;
  private Long merchantId;
  private String merchantRequestNo;
  private String channel;
  private String subject;
  private BigDecimal amount;
  private String currency;
  private String status;
  private String faultScenario;
  private String checkoutToken;
  private LocalDateTime expiresAt;
  private LocalDateTime paidAt;
  private LocalDateTime closedAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getChannelTransactionNo() { return channelTransactionNo; }
  public void setChannelTransactionNo(String value) { this.channelTransactionNo = value; }
  public Long getMerchantId() { return merchantId; }
  public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
  public String getMerchantRequestNo() { return merchantRequestNo; }
  public void setMerchantRequestNo(String value) { this.merchantRequestNo = value; }
  public String getChannel() { return channel; }
  public void setChannel(String channel) { this.channel = channel; }
  public String getSubject() { return subject; }
  public void setSubject(String subject) { this.subject = subject; }
  public BigDecimal getAmount() { return amount; }
  public void setAmount(BigDecimal amount) { this.amount = amount; }
  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getFaultScenario() { return faultScenario; }
  public void setFaultScenario(String faultScenario) { this.faultScenario = faultScenario; }
  public String getCheckoutToken() { return checkoutToken; }
  public void setCheckoutToken(String checkoutToken) { this.checkoutToken = checkoutToken; }
  public LocalDateTime getExpiresAt() { return expiresAt; }
  public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
  public LocalDateTime getPaidAt() { return paidAt; }
  public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
  public LocalDateTime getClosedAt() { return closedAt; }
  public void setClosedAt(LocalDateTime closedAt) { this.closedAt = closedAt; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
