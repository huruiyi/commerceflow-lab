package vip.fairy.commerceflow.commerce.cart.domain;

import java.math.BigDecimal;

public class CheckoutCartItem {

  /** Cart entry locked during checkout and removed after order creation. */
  private Long id;
  private Long productId;
  private Long merchantId;
  private String productName;
  private String thumbnailUrl;
  private BigDecimal unitPrice;
  private int quantity;

  public Long getId() {
    return id;
  }

  public Long getProductId() {
    return productId;
  }

  public Long getMerchantId() {
    return merchantId;
  }

  public String getProductName() {
    return productName;
  }

  public String getThumbnailUrl() {
    return thumbnailUrl;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public int getQuantity() {
    return quantity;
  }

  public CheckoutCartItem withProductFacts(Long merchantId, String name, String thumbnailUrl, BigDecimal price) {
    CheckoutCartItem item = new CheckoutCartItem();
    item.id = id;
    item.productId = productId;
    item.quantity = quantity;
    item.merchantId = merchantId;
    item.productName = name;
    item.thumbnailUrl = thumbnailUrl;
    item.unitPrice = price;
    return item;
  }
}
