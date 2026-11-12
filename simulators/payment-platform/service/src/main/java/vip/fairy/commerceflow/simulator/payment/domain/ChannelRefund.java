package vip.fairy.commerceflow.simulator.payment.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模拟支付渠道的退款单实体，记录退款状态与故障实验结果。
 */
@TableName("channel_refund")
public class ChannelRefund {

  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  private Long id;

  /**
   * 模拟渠道生成的唯一退款编号。
   */
  @TableId(type = IdType.AUTO)
  private String channelRefundNo;
  /**
   * 关联支付商户的内部主键。
   */
  private Long merchantId;
  /**
   * 商户侧退款请求编号，用于幂等。
   */
  private String merchantRefundNo;
  /**
   * 模拟渠道生成的唯一交易编号。
   */
  private String channelTransactionNo;
  /**
   * 交易或退款金额，单位与 currency 指定的币种一致。
   */
  private BigDecimal amount;
  /**
   * ISO 4217 币种代码，如 CNY。
   */
  private String currency;
  /**
   * 退款原因的稳定业务代码。
   */
  private String reasonCode;
  /**
   * 当前业务状态，取值由所属表的状态机约束。
   */
  private String status;
  /**
   * 学习实验使用的故障注入场景；正常流程为空。
   */
  private String faultScenario;
  /**
   * 失败原因摘要；成功时为空。
   */
  private String failureReason;
  /**
   * 退款成功时间。
   */
  private LocalDateTime succeededAt;
  /**
   * 退款失败时间。
   */
  private LocalDateTime failedAt;
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
