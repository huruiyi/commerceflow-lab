package vip.fairy.commerceflow.commerce.cart.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 购物车条目持久化实体，记录用户对商品的待结算数量。
 */
@TableName("customer_orders")
public class CustomerOrder {
  /**
   * 数据库主键；仅用于内部关联，不作为对外业务编号。
   */
  @TableId(type = IdType.AUTO)
  private Long id;
  /**
   * 所属商城用户的内部主键。
   */
  private Long userId;
  /**
   * 关联商品的内部主键。
   */
  private Long productId;
  /**
   * 记录创建时间。
   */
  private Instant createdAt;
  /**
   * 用户对购物车条目的备注。
   */
  private String note;
  /**
   * 待结算商品数量，默认为 1。
   */
  private int quantity = 1;

  protected CustomerOrder() {
  }

  public CustomerOrder(Long userId, Long productId, Instant createdAt, String note, int quantity) {
    this.userId = userId;
    this.productId = productId;
    this.createdAt = createdAt;
    this.note = note;
    this.quantity = quantity;
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getProductId() {
    return productId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public String getNote() {
    return note;
  }

  public int getQuantity() {
    return quantity;
  }

  public void changeQuantity(int quantity) {
    this.quantity = quantity;
  }
}
