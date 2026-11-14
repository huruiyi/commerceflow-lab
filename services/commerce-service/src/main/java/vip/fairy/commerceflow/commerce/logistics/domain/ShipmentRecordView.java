package vip.fairy.commerceflow.commerce.logistics.domain;

import java.time.Instant;

/**
 * 商城侧运单当前状态的持久化投影。
 */
public class ShipmentRecordView {

  /** 商城运单记录自增主键。 */
  private Long id;
  /** 运单所属商城订单主键。 */
  private Long orderId;
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
