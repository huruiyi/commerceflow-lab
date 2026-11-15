package vip.fairy.commerceflow.catalog.domain;

import com.baomidou.mybatisplus.annotation.TableName;

/** 商品与语言区域组成复合键的本地化文案。 */
@TableName("product_translations")
public class ProductTranslation {

  /** 商品稳定内部标识。 */
  private Long productId;
  /** IETF 风格语言区域标识。 */
  private String locale;
  /** 本地化名称。 */
  private String name;
  /** 本地化摘要。 */
  private String brief;
  /** 本地化说明。 */
  private String description;

  public Long getProductId() { return productId; }
  public String getLocale() { return locale; }
  public String getName() { return name; }
  public String getBrief() { return brief; }
  public String getDescription() { return description; }
}
