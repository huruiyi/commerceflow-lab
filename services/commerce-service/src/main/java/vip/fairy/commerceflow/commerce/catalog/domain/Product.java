package vip.fairy.commerceflow.commerce.catalog.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

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
  /**
   * 当前商品所属商家的稳定内部标识；服务化后不依赖 identity 数据库外键。
   */
  private Long merchantId;
  /**
   * 商品对外可见状态；当前只允许 ACTIVE 与 INACTIVE。
   */
  private String lifecycleStatus;
  /**
   * 商家维护使用的乐观并发版本，也是商品事件的业务版本。
   */
  private long businessVersion;
  /**
   * 商品主数据最后更新时间。
   */
  private Instant updatedAt;

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

  public Long getMerchantId() {
    return merchantId;
  }

  public String getLifecycleStatus() {
    return lifecycleStatus;
  }

  public long getBusinessVersion() {
    return businessVersion;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
