package vip.fairy.commerceflow.commerce.inventory.domain;

public class InventoryReservation {
  private Long id;
  private Long orderId;
  private Long productId;
  private int quantity;
  private String status;

  public Long getId() {
    return id;
  }

  public Long getOrderId() {
    return orderId;
  }

  public Long getProductId() {
    return productId;
  }

  public int getQuantity() {
    return quantity;
  }

  public String getStatus() {
    return status;
  }
}
