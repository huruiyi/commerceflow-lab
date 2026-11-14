package vip.fairy.commerceflow.commerce.inventory.domain;

import java.time.Instant;

public class InventoryMovementView {

  private Long id;
  private Long orderId;
  private Long productId;
  private String movementType;
  private int quantity;
  private int availableAfter;
  private int reservedAfter;
  private int soldAfter;
  private String detail;
  private Instant createdAt;

  public Long getId() {
    return id;
  }

  public Long getOrderId() {
    return orderId;
  }

  public Long getProductId() {
    return productId;
  }

  public String getMovementType() {
    return movementType;
  }

  public int getQuantity() {
    return quantity;
  }

  public int getAvailableAfter() {
    return availableAfter;
  }

  public int getReservedAfter() {
    return reservedAfter;
  }

  public int getSoldAfter() {
    return soldAfter;
  }

  public String getDetail() {
    return detail;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
