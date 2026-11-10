package com.tanerinal.education.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;

@TableName("products")
public class Product {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String name;
  private String brief;
  private String description;
  private BigDecimal price;
  private String imageUrl;
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

  public Long getId() { return id; }
  public String getName() { return name; }
  public String getBrief() { return brief; }
  public String getDescription() { return description; }
  public BigDecimal getPrice() { return price; }
  public String getImageUrl() { return imageUrl; }
  public String getThumbnailUrl() { return thumbnailUrl; }
}
