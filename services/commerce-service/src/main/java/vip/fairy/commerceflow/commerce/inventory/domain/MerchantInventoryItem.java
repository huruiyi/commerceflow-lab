package vip.fairy.commerceflow.commerce.inventory.domain;

import java.math.BigDecimal;

public class MerchantInventoryItem {

  private Long productId;
  private String productName;
  private String thumbnailUrl;
  private BigDecimal price;
  private int stockAvailable;
  private int stockReserved;
  private int stockSold;

  public Long getProductId() {
    return productId;
  }

  public String getProductName() {
    return productName;
  }

  public String getThumbnailUrl() {
    return thumbnailUrl;
  }

  public BigDecimal getPrice() {
    return price;
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
