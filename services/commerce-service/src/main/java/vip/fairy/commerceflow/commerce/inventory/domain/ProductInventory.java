package vip.fairy.commerceflow.commerce.inventory.domain;

public class ProductInventory {

  private Long id;
  private int stockAvailable;
  private int stockReserved;
  private int stockSold;

  public Long getId() {
    return id;
  }

  public int getStockAvailable() {
    return stockAvailable;
  }

  public int getStockReserved() {
    return stockReserved;
  }

  public int getStockSold() {
    return stockSold;
  }
}
