package vip.fairy.commerceflow.commerce.logistics.domain;

import java.time.Instant;

/**
 * 商城侧运单当前状态的持久化投影。
 */
public class ShipmentRecordView {

  /** 最新已应用的渠道运单详情版本。 */
  private long shipmentVersion;

  public long getShipmentVersion() {
    return shipmentVersion;
  }

  /** 商城运单记录自增主键。 */
  private Long id;
  /** 运单所属商城订单主键。 */
  private Long orderId;
  /** 逆向运单关联的售后单号，正向运单为空。 */
  private String afterSaleId;
  /** 退货运单补建时发送给物流平台的原订单编号。 */
  private String orderNumber;
  /** 最近应用的退货物流事件发生时间。 */
  private Instant lastEventAt;
  /** 商城生成并发送给物流平台的运单号。 */
  private String merchantShipmentNo;
  /** 物流平台返回的渠道运单号。 */
  private String platformShipmentNo;
  /** 承运商代码或名称。 */
  private String carrier;
  /** 承运商查件单号。 */
  private String trackingNumber;
  /** 当前物流投影状态，不允许被乱序旧事件倒退。 */
  private String status;
  /** 商城确认发货时间。 */
  private Instant shippedAt;
  /** 物流平台首次确认送达时间。 */
  private Instant deliveredAt;

  public Long getId() {
    return id;
  }

  public Long getOrderId() {
    return orderId;
  }

  public String getAfterSaleId() {
    return afterSaleId;
  }

  public String getOrderNumber() {
    return orderNumber;
  }

  public Instant getLastEventAt() {
    return lastEventAt;
  }

  public String getMerchantShipmentNo() {
    return merchantShipmentNo;
  }

  public String getPlatformShipmentNo() {
    return platformShipmentNo;
  }

  public String getCarrier() {
    return carrier;
  }

  public String getTrackingNumber() {
    return trackingNumber;
  }

  public String getStatus() {
    return status;
  }

  public Instant getShippedAt() {
    return shippedAt;
  }

  public Instant getDeliveredAt() {
    return deliveredAt;
  }
}
