package vip.fairy.commerceflow.catalog.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

/** 商品服务拥有的商品主数据。 */
@TableName("products")
public class Product {

  /** 商品稳定内部标识。 */
  @TableId(type = IdType.INPUT)
  private Long id;
  /** 默认语言商品名称。 */
  private String name;
  /** 默认语言商品摘要。 */
  private String brief;
  /** 默认语言商品说明。 */
  private String description;
  /** 标准单价。 */
  private BigDecimal price;
  /** 详情图片地址。 */
  private String imageUrl;
  /** 缩略图地址。 */
  private String thumbnailUrl;
  /** 所属商家稳定标识。 */
  private Long merchantId;
  /** ACTIVE 或 INACTIVE。 */
  private String lifecycleStatus;
  /** 乐观锁与事件排序使用的业务版本。 */
  private long businessVersion;
  /** 最后更新时间。 */
  private Instant updatedAt;

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getBrief() { return brief; }
  public String getDescription() { return description; }
  public BigDecimal getPrice() { return price; }
  public String getImageUrl() { return imageUrl; }
  public String getThumbnailUrl() { return thumbnailUrl; }
  public Long getMerchantId() { return merchantId; }
  public String getLifecycleStatus() { return lifecycleStatus; }
  public long getBusinessVersion() { return businessVersion; }
  public Instant getUpdatedAt() { return updatedAt; }
}
