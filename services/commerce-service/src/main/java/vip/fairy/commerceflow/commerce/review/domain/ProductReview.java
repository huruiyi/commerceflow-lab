package vip.fairy.commerceflow.commerce.review.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 商品评价持久化实体，每个订单商品条目最多对应一条评价。
 */
@TableName("product_reviews")
public class ProductReview {
  /**
   * 数据库自增主键，仅用于内部关联。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 被评价订单商品条目的内部主键，同时承担防止重复评价的唯一约束。
   */
  private Long orderItemId;
  /**
   * 被评价商品的内部主键，用于商品维度聚合。
   */
  private Long productId;
  /**
   * 提交评价的客户内部主键。
   */
  private Long customerId;
  /**
   * 评价星级，取值范围为 1～5。
   */
  private int rating;
  /**
   * 客户评价正文，最长 1000 个字符。
   */
  private String comment;
  /**
   * 评价创建时间，由数据库默认值生成。
   */
  private Instant createdAt;

  protected ProductReview() {
  }

  public ProductReview(Long orderItemId, Long productId, Long customerId, int rating, String comment) {
    this.orderItemId = orderItemId;
    this.productId = productId;
    this.customerId = customerId;
    this.rating = rating;
    this.comment = comment;
  }

  public Long getId() {
    return id;
  }

  public Long getOrderItemId() {
    return orderItemId;
  }

  public Long getProductId() {
    return productId;
  }

  public Long getCustomerId() {
    return customerId;
  }

  public int getRating() {
    return rating;
  }

  public String getComment() {
    return comment;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
