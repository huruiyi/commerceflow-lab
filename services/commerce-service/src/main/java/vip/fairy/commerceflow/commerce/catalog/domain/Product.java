package vip.fairy.commerceflow.commerce.catalog.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

/**
 * 商品主数据持久化实体，记录展示信息与标准售价。
 */
@TableName("products")
public class Product {
  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 商品展示名称。
   */
  private String name;
  /**
   * 商品列表使用的简短卖点。
   */
  private String brief;
  /**
   * 商品详情或业务对象的完整说明。
   */
  private String description;
  /**
   * 商品标准售价，币种由商城价格配置约定。
   */
  private BigDecimal price;
  /**
   * 商品详情图片地址。
   */
  private String imageUrl;
  /**
   * 商品列表缩略图地址。
   */
  private String thumbnailUrl;

  protected Product() {
  }

  public Product(String name, String brief, String description, BigDecimal price,
                 String imageUrl, String thumbnailUrl) {
    this.name = name;
    this.brief = brief;
    this.description = description;
    this.price = price;
    this.imageUrl = imageUrl;
    this.thumbnailUrl = thumbnailUrl;
  }

  public Long getId() {
    return id;
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

  public BigDecimal getPrice() {
    return price;
  }

  public String getImageUrl() {
    return imageUrl;
  }

  public String getThumbnailUrl() {
    return thumbnailUrl;
  }
}
