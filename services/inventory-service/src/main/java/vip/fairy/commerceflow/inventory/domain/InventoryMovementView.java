package vip.fairy.commerceflow.inventory.domain;

import java.time.Instant;

public class InventoryMovementView {

  /** Inventory-owned movement row identifier. */
  private Long id;
  /** Ordering identifier associated with the movement, when applicable. */
  private Long orderId;
  /** Catalog product identifier whose balance changed. */
  private Long productId;
  /** Stable movement category such as MANUAL_INCREASE. */
  private String movementType;
  /** Absolute number of units moved. */
  private int quantity;
  /** Available balance immediately after the movement. */
  private int availableAfter;
  /** Reserved balance immediately after the movement. */
  private int reservedAfter;
  /** Sold balance immediately after the movement. */
  private int soldAfter;
  /** Operator or workflow reason recorded with the movement. */
  private String detail;
  /** Inventory database commit time in UTC. */
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
