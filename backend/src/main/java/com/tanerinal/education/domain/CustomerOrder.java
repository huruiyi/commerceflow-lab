package com.tanerinal.education.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;

@TableName("customer_orders")
public class CustomerOrder {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long userId;
  private Long productId;
  private Instant createdAt;
  private String note;
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

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public Long getProductId() { return productId; }
  public Instant getCreatedAt() { return createdAt; }
  public String getNote() { return note; }
  public int getQuantity() { return quantity; }
  public void changeQuantity(int quantity) { this.quantity = quantity; }
}
