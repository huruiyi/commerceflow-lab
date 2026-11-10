package com.tanerinal.education.domain;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;

@Entity
@Table(name = "products")
public class Product {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 160)
  private String name;

  @Column(nullable = false, length = 300)
  private String brief;

  @Column(nullable = false, length = 2000)
  private String description;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal price;

  @Column(nullable = false, length = 500)
  private String imageUrl;

  @Column(nullable = false, length = 500)
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
