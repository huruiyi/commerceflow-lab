package vip.fairy.commerceflow.commerce.logistics.domain;

import java.time.Instant;

public class ShipmentRecordView {
  private Long id;
  private Long orderId;
  private String merchantShipmentNo;
  private String platformShipmentNo;
  private String carrier;
  private String trackingNumber;
  private String status;
  private Instant shippedAt;
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
