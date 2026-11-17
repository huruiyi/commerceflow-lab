package vip.fairy.commerceflow.inventory.domain;

import java.math.BigDecimal;

public class MerchantInventoryItem {

  /** Catalog product identifier owned by the merchant boundary. */
  private Long productId;
  /** Product name snapshot supplied by catalog-service. */
  private String productName;
  /** Product thumbnail snapshot supplied by catalog-service. */
  private String thumbnailUrl;
  /** Current catalog unit price snapshot. */
  private BigDecimal price;
  /** Units currently available for reservation. */
  private int stockAvailable;
  /** Units reserved by unpaid orders. */
  private int stockReserved;
  /** Units committed to paid orders. */
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
