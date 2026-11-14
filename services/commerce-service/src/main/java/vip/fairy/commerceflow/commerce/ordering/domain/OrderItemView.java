package vip.fairy.commerceflow.commerce.ordering.domain;

import java.math.BigDecimal;

/**
 * 订单商品快照投影，保留下单时的商品、商家、价格和数量事实。
 */
public class OrderItemView {

  /**
   * 订单商品条目内部主键。
   */
  private Long id;
  /**
   * 下单时关联的商品内部主键。
   */
  private Long productId;
  /**
   * 履约该商品的商家内部主键。
   */
  private Long merchantId;
  /**
   * 下单时冻结的商品名称。
   */
  private String productName;
  /**
   * 下单时冻结的商品缩略图地址。
   */
  private String thumbnailUrl;
  /**
   * 下单时的商品单价，币种继承订单币种。
   */
  private BigDecimal unitPrice;
  /**
   * 购买数量，必须为正整数。
   */
  private int quantity;
  /**
   * 条目小计，等于单价乘以数量。
   */
  private BigDecimal subtotal;

  public Long getId() {
    return id;
  }

  public Long getProductId() {
    return productId;
  }

  public String getProductName() {
    return productName;
  }

  public Long getMerchantId() {
    return merchantId;
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

  public BigDecimal getSubtotal() {
    return subtotal;
  }
}
