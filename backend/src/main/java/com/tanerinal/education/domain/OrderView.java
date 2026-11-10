package com.tanerinal.education.domain;

import java.math.BigDecimal;
import java.time.Instant;

public class OrderView {
  private Long id;
  private Instant createdAt;
  private String productName;
  private BigDecimal price;
  private int quantity;
  private String thumbnailUrl;
  private String note;

  public Long getId() { return id; }
  public Instant getCreatedAt() { return createdAt; }
  public String getProductName() { return productName; }
  public BigDecimal getPrice() { return price; }
  public int getQuantity() { return quantity; }
  public String getThumbnailUrl() { return thumbnailUrl; }
  public String getNote() { return note; }
}
