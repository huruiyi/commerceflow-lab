package vip.fairy.commerceflow.commerce.ordering.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 商城订单数据库投影，承载订单状态机及各生命周期时间点。
 */
public class ShopOrderView {
  /**
   * 订单内部主键。
   */
  private Long id;
  /**
   * 面向用户展示且全局唯一的订单编号。
   */
  private String orderNumber;
  /**
   * 下单客户的内部主键。
   */
  private Long customerId;
  /**
   * 当前订单状态，取值遵循订单生命周期状态机。
   */
  private String status;
  /**
   * 订单应付总金额，币种由 {@link #currency} 指定。
   */
  private BigDecimal totalAmount;
  /**
   * ISO 4217 货币代码，例如 CNY。
   */
  private String currency;
  /**
   * 订单创建时间。
   */
  private Instant createdAt;
  /**
   * 订单记录最后更新时间。
   */
  private Instant updatedAt;
  /**
   * 商家确认接单时间，未确认时为空。
   */
  private Instant confirmedAt;
  /**
   * 商家发货时间，未发货时为空。
   */
  private Instant shippedAt;
  /**
   * 当前承运商名称或代码。
   */
  private String carrier;
  /**
   * 当前物流运单号。
   */
  private String trackingNumber;
  /**
   * 整单支付截止时间，超过该时间后进入自动关单流程。
   */
  private Instant paymentDueAt;
  /**
   * 订单完成取消的时间，未取消时为空。
   */
  private Instant cancelledAt;
  /**
   * 订单取消原因或迟到支付恢复说明。
   */
  private String cancelReason;
  /**
   * 客户手动或系统自动确认收货时间。
   */
  private Instant receivedAt;
  /**
   * 收货观察期结束后的订单完成时间。
   */
  private Instant completedAt;
  /**
   * 物流平台首次确认送达的时间，不等同于客户确认收货时间。
   */
  private Instant logisticsDeliveredAt;

  public Long getId() {
    return id;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public Long getCustomerId() {
    return customerId;
  }

  public String getStatus() {
    return status;
  }

  public BigDecimal getTotalAmount() {
    return totalAmount;
  }

  public String getCurrency() {
    return currency;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getConfirmedAt() {
    return confirmedAt;
  }

  public Instant getShippedAt() {
    return shippedAt;
  }

  public String getCarrier() {
    return carrier;
  }

  public String getTrackingNumber() {
    return trackingNumber;
  }

  public Instant getPaymentDueAt() {
    return paymentDueAt;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public String getCancelReason() {
    return cancelReason;
  }

  public Instant getReceivedAt() {
    return receivedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Instant getLogisticsDeliveredAt() {
    return logisticsDeliveredAt;
  }
}
