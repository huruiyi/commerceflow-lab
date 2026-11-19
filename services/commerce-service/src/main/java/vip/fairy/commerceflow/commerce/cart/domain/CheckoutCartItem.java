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

  public BigDecimal subtotal() {
    return unitPrice.multiply(BigDecimal.valueOf(quantity));
  }
}
