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
  @TableId(type = IdType.AUTO)
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
  /** Idempotency key supplied by the merchant at registration. */
  private String createRequestId;
  /** Digest of the original registration fields, independent of subsequent edits. */
  private String createFingerprint;
  /** 内部 SKU 销售单元所属的公开商品。为空时本行即为公开商品。 */
  private Long listingProductId;
  /** SPU 的固定显示器参数 JSON。独立库存销售单元不复制该资料。 */
  private String monitorProfile;

  protected Product() {
  }

  public Product(Long merchantId, String requestId, String fingerprint, String name,
                 String description, BigDecimal price, String imageUrl) {
    this.merchantId = merchantId;
    this.createRequestId = requestId;
    this.createFingerprint = fingerprint;
    this.name = name;
    this.brief = name;
    this.description = description;
    this.price = price;
    this.imageUrl = imageUrl;
    this.thumbnailUrl = imageUrl;
    this.lifecycleStatus = "INACTIVE";
    this.businessVersion = 1;
  }

  public String getCreateFingerprint() {
    return createFingerprint;
  }

  public static Product variant(Product listing, String name, BigDecimal price) {
    var variant = new Product(listing.merchantId, null, null, name, listing.description, price, listing.imageUrl);
    variant.listingProductId = listing.id;
    variant.brief = listing.brief;
    variant.thumbnailUrl = listing.thumbnailUrl;
    return variant;
  }

  public Product asSellable() {
    if (listingProductId == null) return this;
    var copy = new Product(merchantId, null, null, name, description, price, imageUrl);
    copy.id = id;
    copy.listingProductId = listingProductId;
    copy.brief = brief;
    copy.thumbnailUrl = thumbnailUrl;
    copy.lifecycleStatus = "ACTIVE";
    copy.businessVersion = businessVersion;
    copy.updatedAt = updatedAt;
    return copy;
  }

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
  public String getMonitorProfile() {
    return monitorProfile;
  }

  public Long getListingProductId() {
    return listingProductId;
  }
}
