package lab.commerceflow.storefront.domain;

import java.math.BigDecimal;

public class OrderItemView {
  private Long id; private Long productId; private Long merchantId; private String productName; private String thumbnailUrl;
  private BigDecimal unitPrice; private int quantity; private BigDecimal subtotal;
  public Long getId(){return id;} public Long getProductId(){return productId;} public String getProductName(){return productName;}
  public Long getMerchantId(){return merchantId;}
  public String getThumbnailUrl(){return thumbnailUrl;} public BigDecimal getUnitPrice(){return unitPrice;}
  public int getQuantity(){return quantity;} public BigDecimal getSubtotal(){return subtotal;}
}
