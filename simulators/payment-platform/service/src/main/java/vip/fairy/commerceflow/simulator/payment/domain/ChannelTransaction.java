package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟支付渠道的交易单实体，承载收款、关单和超时状态。
 */
@TableName("channel_transaction")
public class ChannelTransaction {

  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 模拟渠道生成的唯一交易编号。
   */
  private String channelTransactionNo;
  /**
   * 关联支付商户的内部主键。
   */
  private Long merchantId;
  /**
   * 商户侧支付请求编号，用于幂等。
   */
  private String merchantRequestNo;
  /**
   * 模拟支付渠道代码，如 alipay、wechat-pay 或 union-pay。
   */
  private String channel;
  /**
   * 收银台展示的交易标题。
   */
  private String subject;
  /**
   * 交易或退款金额，单位与 currency 指定的币种一致。
   */
  private BigDecimal amount;
  /**
   * ISO 4217 币种代码，如 CNY。
   */
  private String currency;
  /**
   * 当前业务状态，取值由所属表的状态机约束。
   */
  private String status;
  /**
   * 学习实验使用的故障注入场景；正常流程为空。
   */
  private String faultScenario;
  /**
   * 收银台访问令牌，用于定位待支付交易。
   */
  private String checkoutToken;
  /**
   * 交易或凭据的过期时间。
   */
  private LocalDateTime expiresAt;
  /**
   * 渠道确认支付成功的时间。
   */
  private LocalDateTime paidAt;
  /**
   * 交易关闭时间。
   */
  private LocalDateTime closedAt;
  /**
   * 记录创建时间。
   */
  private LocalDateTime createdAt;
  /**
   * 记录最后更新时间。
   */
  private LocalDateTime updatedAt;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getChannelTransactionNo() {
    return channelTransactionNo;
  }

  public void setChannelTransactionNo(String value) {
    this.channelTransactionNo = value;
  }

  public Long getMerchantId() {
    return merchantId;
  }

  public void setMerchantId(Long merchantId) {
    this.merchantId = merchantId;
  }

  public String getMerchantRequestNo() {
    return merchantRequestNo;
  }

  public void setMerchantRequestNo(String value) {
    this.merchantRequestNo = value;
  }

  public String getChannel() {
    return channel;
  }

  public void setChannel(String channel) {
    this.channel = channel;
  }

  public String getSubject() {
    return subject;
  }

  public void setSubject(String subject) {
    this.subject = subject;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
  }

  public String getFaultScenario() {
    return faultScenario;
  }

  public void setFaultScenario(String faultScenario) {
    this.faultScenario = faultScenario;
  }

  public String getCheckoutToken() {
    return checkoutToken;
  }

  public void setCheckoutToken(String checkoutToken) {
    this.checkoutToken = checkoutToken;
  }

  public LocalDateTime getExpiresAt() {
    return expiresAt;
  }

  public void setExpiresAt(LocalDateTime expiresAt) {
    this.expiresAt = expiresAt;
  }

  public LocalDateTime getPaidAt() {
    return paidAt;
  }

  public void setPaidAt(LocalDateTime paidAt) {
    this.paidAt = paidAt;
  }

  public LocalDateTime getClosedAt() {
    return closedAt;
  }

  public void setClosedAt(LocalDateTime closedAt) {
    this.closedAt = closedAt;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
