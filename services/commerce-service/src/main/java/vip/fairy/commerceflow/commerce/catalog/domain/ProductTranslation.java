package vip.fairy.commerceflow.commerce.catalog.domain;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 商品本地化文案持久化实体，以商品和语言区域组成复合业务键。
 */
@TableName("product_translations")
public class ProductTranslation {
  /**
   * 关联商品的内部主键。
   */
  private Long productId;
  /**
   * IETF 风格的语言区域标识，例如 {@code zh-CN}。
   */
  private String locale;
  /**
   * 当前语言下的商品展示名称。
   */
  private String name;
  /**
   * 当前语言下用于商品列表的简短卖点。
   */
  private String brief;
  /**
   * 当前语言下的商品完整说明。
   */
  private String description;

  public Long getProductId() {
    return productId;
  }

  public String getLocale() {
    return locale;
  }

  public String getName() {
    return name;
  }

  public String getBrief() {
    return brief;
  }

  public String getDescription() {
    return description;
  }
}
